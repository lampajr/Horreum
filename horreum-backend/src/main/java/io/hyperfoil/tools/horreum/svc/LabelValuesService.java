package io.hyperfoil.tools.horreum.svc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.hyperfoil.tools.horreum.api.data.ExportedLabelValues;
import io.hyperfoil.tools.horreum.hibernate.JsonBinaryType;
import io.hyperfoil.tools.horreum.hibernate.JsonbSetType;

/**
 * Utility service that retrieves label values for specific entities, e.g., Tests or Runs
 */
@ApplicationScoped
public class LabelValuesService {
    protected static final String FILTER_PREFIX = "WHERE ";
    protected static final String FILTER_SEPARATOR = " AND ";
    protected static final String FILTER_BEFORE = " combined.stop < :before";
    protected static final String FILTER_AFTER = " combined.start > :after";
    protected static final String LABEL_VALUES_FILTER_CONTAINS_JSON = "combined.value @> :filter";
    protected static final String LABEL_ORDER_PREFIX = "order by ";
    protected static final String LABEL_ORDER_START = "combined.start";
    protected static final String LABEL_ORDER_STOP = "combined.stop";
    protected static final String LABEL_ORDER_JSONPATH = "jsonb_path_query(combined.value,CAST( :orderBy as jsonpath))";

    //a solution does exist! https://github.com/spring-projects/spring-data-jpa/issues/2551
    //use @\\?\\? to turn into a @? in the query
    protected static final String LABEL_VALUES_FILTER_MATCHES_NOT_NULL = "combined.value @\\?\\? CAST( :filter as jsonpath)";

    protected static final String LABEL_VALUES_QUERY = """
            WITH
            combined as (
            SELECT label.name AS labelName, lv.value AS value, runId, dataset.id AS datasetId, dataset.start AS start, dataset.stop AS stop
                     FROM dataset
                     LEFT JOIN label_values lv ON dataset.id = lv.dataset_id
                     LEFT JOIN label ON label.id = lv.label_id
                     WHERE dataset.testid = :testId
                        AND (label.id IS NULL OR (:filteringLabels AND label.filtering) OR (:metricLabels AND label.metrics)) INCLUDE_EXCLUDE_PLACEHOLDER
            ) select * from combined FILTER_PLACEHOLDER ORDER_PLACEHOLDER LIMIT_PLACEHOLDER
            """;

    @Inject
    EntityManager em;

    protected FilterDef getFilterDef(JsonNode filter, Instant before, Instant after, boolean multiFilter,
            Function<String, List<ExportedLabelValues>> checkFilter) {

        StringBuilder filterSqlBuilder = new StringBuilder();
        Set<String> names = new HashSet<>();
        ObjectNode objectNode = null;
        List<String> assumeMulti = new ArrayList<>();

        if (filter != null && filter.getNodeType() == JsonNodeType.OBJECT) {
            objectNode = (ObjectNode) filter;
            if (multiFilter) {
                objectNode.fields().forEachRemaining(e -> {
                    String key = e.getKey();
                    JsonNode value = e.getValue();
                    // if multiFilter and array value -> need to check if at least one of those values
                    // matches in any of the labelValues objects
                    if (value.getNodeType() == JsonNodeType.ARRAY) { //check if there are any matches
                        ObjectNode arrayFilter = new JsonNodeFactory(false).objectNode().set(key, value);
                        // this is going to execute the checkFilter which is the labelValues call with just this key-value
                        // as filter. This means that it is going to execute the whole logic just to check if no results
                        // are returned. Which means you are actually trying a multi filter check and not an exact array match!!
                        // TODO: do we really need this??
                        List<ExportedLabelValues> found = checkFilter.apply(arrayFilter.asText());
                        if (found.isEmpty()) {
                            assumeMulti.add(key);
                        }
                    }
                });

                // if assumeMulti has been populated, let's remove those objects from the original objectNode
                if (!assumeMulti.isEmpty()) {
                    assumeMulti.forEach(objectNode::remove);
                }
            }

            if (!objectNode.isEmpty()) {
                filterSqlBuilder.append(LABEL_VALUES_FILTER_CONTAINS_JSON);
                names.add("filter");
            }

            if (!assumeMulti.isEmpty()) {
                for (int i = 0; i < assumeMulti.size(); i++) {
                    if (!filterSqlBuilder.isEmpty()) {
                        filterSqlBuilder.append(FILTER_SEPARATOR);
                    }
                    filterSqlBuilder.append(" jsonb_path_query_first(combined.value,CAST( :key")
                            .append(i).append(" as jsonpath)) = ANY(:value").append(i).append(") ");
                    names.add("key" + i);
                    names.add("value" + i);
                }
            }
        } else if (filter != null && filter.getNodeType() == JsonNodeType.STRING) {
            Util.CheckResult jsonpathResult = Util.castCheck(filter.asText(), "jsonpath", em);
            if (jsonpathResult.ok()) {
                filterSqlBuilder.append(LABEL_VALUES_FILTER_MATCHES_NOT_NULL);
                names.add("filter");
            } else {
                throw new IllegalArgumentException(jsonpathResult.message());
            }
        }

        if (before != null) {
            if (!filterSqlBuilder.isEmpty()) {
                filterSqlBuilder.append(FILTER_SEPARATOR);
            }
            filterSqlBuilder.append(FILTER_BEFORE);
            names.add("before");
        }

        if (after != null) {
            if (!filterSqlBuilder.isEmpty()) {
                filterSqlBuilder.append(FILTER_SEPARATOR);
            }
            filterSqlBuilder.append(FILTER_AFTER);
            names.add("after");
        }

        String filterSql = "";
        if (!filterSqlBuilder.isEmpty()) {
            filterSql = FILTER_PREFIX + filterSqlBuilder;
        }

        return new FilterDef(filterSql, objectNode, names, assumeMulti);
    }

    @Transactional
    public List<ExportedLabelValues> labelValuesByTest(int testId, String filter, String before, String after,
            boolean filtering,
            boolean metrics, String sort, String direction, Integer limit, int page, List<String> include, List<String> exclude,
            boolean multiFilter) {

        JsonNode filterObject = Util.getFilterObject(filter);
        Instant beforeInstant = Util.toInstant(before);
        Instant afterInstant = Util.toInstant(after);

        FilterDef filterDef = getFilterDef(filterObject, beforeInstant, afterInstant, multiFilter,
                (str) -> labelValuesByTest(testId, str,
                        before, after, filtering, metrics, sort, direction, limit, page, include, exclude, false));

        String filterSql = filterDef.sql();
        if (filterDef.filterObject() != null) {
            filterObject = filterDef.filterObject();
        }

        String includeExcludeSql = "";
        List<String> mutableInclude = new ArrayList<>(include);

        if (include != null && !include.isEmpty()) {
            if (exclude != null && !exclude.isEmpty()) {
                mutableInclude.removeAll(exclude);
            }
            if (!mutableInclude.isEmpty()) {
                includeExcludeSql = "AND label.name in :include";
            }
        }
        //includeExcludeSql is empty if include did not contain entries after exclude removal
        if (includeExcludeSql.isEmpty() && exclude != null && !exclude.isEmpty()) {
            includeExcludeSql = "AND label.name NOT in :exclude";
        }

        if (filterSql.isBlank() && filter != null && !filter.isBlank()) {
            //TODO there was an error with the filter, do we return that info to the user?
        }

        // by default order by runId
        String orderSql = LABEL_ORDER_PREFIX + "combined.runId DESC";
        ;
        String orderDirection = direction.equalsIgnoreCase("ascending") ? "ASC" : "DESC";
        if ("start".equalsIgnoreCase(sort)) {
            orderSql = LABEL_ORDER_PREFIX + LABEL_ORDER_START + " " + orderDirection + ", combined.runId DESC";
        } else if ("stop".equalsIgnoreCase(sort)) {
            orderSql = LABEL_ORDER_PREFIX + LABEL_ORDER_STOP + " " + orderDirection + ", combined.runId DESC";
        } else {
            if (!sort.isBlank()) {
                Util.CheckResult jsonpathResult = Util.castCheck(sort, "jsonpath", em);
                if (jsonpathResult.ok()) {
                    orderSql = LABEL_ORDER_PREFIX + LABEL_ORDER_JSONPATH + " " + orderDirection + ", combined.runId DESC";
                }
            }

        }
        String limitSql = "";
        if (limit != null) {
            limitSql = "limit " + limit + " offset " + limit * Math.max(0, page);
        }
        String sql = LABEL_VALUES_QUERY
                .replace("FILTER_PLACEHOLDER", filterSql)
                .replace("INCLUDE_EXCLUDE_PLACEHOLDER", includeExcludeSql)
                .replace("ORDER_PLACEHOLDER", orderSql)
                .replace("LIMIT_PLACEHOLDER", limitSql);

        NativeQuery<Object[]> query = (NativeQuery<Object[]>) (em.createNativeQuery(sql))
                .setParameter("testId", testId)
                .setParameter("filteringLabels", filtering)
                .setParameter("metricLabels", metrics);

        if (!filterSql.isEmpty()) {
            if (filterSql.contains(LABEL_VALUES_FILTER_CONTAINS_JSON)) {
                query.unwrap(NativeQuery.class).setParameter("filter", filterObject, JsonBinaryType.INSTANCE);
            } else if (filterSql.contains(LABEL_VALUES_FILTER_MATCHES_NOT_NULL)) {
                query.setParameter("filter", filter);
            }
            if (beforeInstant != null) {
                query.setParameter("before", beforeInstant, StandardBasicTypes.INSTANT);
            }
            if (afterInstant != null) {
                query.setParameter("after", afterInstant, StandardBasicTypes.INSTANT);
            }
        }
        if (!filterDef.multis().isEmpty() && filterDef.filterObject() != null) {
            ObjectNode fullFilterObject = (ObjectNode) Util.getFilterObject(filter);
            for (int i = 0; i < filterDef.multis().size(); i++) {
                String key = filterDef.multis().get(i);
                ArrayNode value = (ArrayNode) fullFilterObject.get(key);
                query.setParameter("key" + i, "$." + key);
                query.setParameter("value" + i, value, JsonbSetType.INSTANCE);
            }
        }
        if (includeExcludeSql.contains(":include")) {
            query.setParameter("include", mutableInclude);
        } else if (includeExcludeSql.contains(":exclude")) {
            query.setParameter("exclude", exclude);
        }
        if (orderSql.contains(LABEL_ORDER_JSONPATH)) {
            query.setParameter("orderBy", sort);
        }
        query
                .addScalar("labelName", String.class)
                .addScalar("value", JsonBinaryType.INSTANCE)
                .addScalar("runId", Integer.class)
                .addScalar("datasetId", Integer.class)
                .addScalar("start", StandardBasicTypes.INSTANT)
                .addScalar("stop", StandardBasicTypes.INSTANT);

        return ExportedLabelValues.parse(query.getResultList());
    }

    /**
     * Utility POJO that contains information about label values filtering
     *
     * @param sql
     * @param filterObject
     * @param names
     * @param multis
     */
    protected record FilterDef(String sql, ObjectNode filterObject, Set<String> names, List<String> multis) {
    }
}
