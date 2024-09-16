package io.hyperfoil.tools.horreum.svc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.hyperfoil.tools.horreum.api.data.ExportedLabelValues;
import io.hyperfoil.tools.horreum.hibernate.JsonBinaryType;
import io.hyperfoil.tools.horreum.hibernate.JsonbSetType;
import io.quarkus.logging.Log;

/**
 * Utility service that retrieves label values for specific entities, e.g., Tests or Runs
 */
@ApplicationScoped
public class LabelValuesService {
    protected static final String FILTER_PREFIX = "WHERE ";
    protected static final String FILTER_SEPARATOR = " AND ";
    protected static final String OR_SEPARATOR = " OR ";
    protected static final String FILTER_BEFORE = " combined.stop < :before";
    protected static final String FILTER_AFTER = " combined.start > :after";
    protected static final String LABEL_VALUES_FILTER_CONTAINS_JSON = "combined.value @> :filter";
    protected static final String LABEL_ORDER_PREFIX = "order by ";
    protected static final String LABEL_ORDER_START = "combined.start";
    protected static final String LABEL_ORDER_STOP = "combined.stop";
    // TODO check usage and if it is still working as expected
    protected static final String LABEL_ORDER_JSONPATH = "jsonb_path_query(combined.value,CAST( :orderBy as jsonpath))";

    //a solution does exist! https://github.com/spring-projects/spring-data-jpa/issues/2551
    //use @\\?\\? to turn into a @? in the query
    protected static final String LABEL_VALUES_FILTER_MATCHES_NOT_NULL = "combined.value @\\?\\? CAST( :filter as jsonpath)";

    protected static final String LABEL_VALUES_QUERY_BY_TEST = """
            WITH
            combined as (
            SELECT label.name AS labelName, lv.value AS value, runId, dataset.id AS datasetId, dataset.start AS start, dataset.stop AS stop
                     FROM dataset
                     LEFT JOIN label_values lv ON dataset.id = lv.dataset_id
                     LEFT JOIN label ON label.id = lv.label_id
                     WHERE dataset.testid = :testId
                        AND (label.id IS NULL OR (:filteringLabels AND label.filtering) OR (:metricLabels AND label.metrics)) INCLUDE_EXCLUDE_PLACEHOLDER
            ) SELECT * from combined FILTER_PLACEHOLDER ORDER_PLACEHOLDER LIMIT_PLACEHOLDER
            """;

    protected static final String LABEL_VALUES_QUERY_BY_RUN = """
            WITH
            combined as (
            SELECT label.name AS labelName, lv.value AS value, runId, dataset.id AS datasetId, dataset.start AS start, dataset.stop AS stop
                     FROM dataset
                     LEFT JOIN label_values lv ON dataset.id = lv.dataset_id
                     LEFT JOIN label ON label.id = lv.label_id
                     WHERE dataset.runid = :runId INCLUDE_EXCLUDE_PLACEHOLDER
            ) SELECT * from combined FILTER_PLACEHOLDER ORDER_PLACEHOLDER LIMIT_PLACEHOLDER
            """;

    protected static final String LABEL_VALUES_DATASETS_BY_TEST_AND_FILTER = """
            SELECT inner_d.id
            FROM dataset inner_d
                LEFT JOIN label_values inner_lv ON inner_d.id = inner_lv.dataset_id
                LEFT JOIN label inner_l ON inner_l.id = inner_lv.label_id
                WHERE inner_d.testid = :testId LABEL_VALUE_FILTER_PLACEHOLDER
                GROUP BY inner_d.id
                HAVING COUNT(*) = :filterKeysCount
            """;

    protected static final String LABEL_VALUES_DATASETS_BY_RUN_AND_FILTER = """
            SELECT inner_d.id
            FROM dataset inner_d
                LEFT JOIN label_values inner_lv ON inner_d.id = inner_lv.dataset_id
                LEFT JOIN label inner_l ON inner_l.id = inner_lv.label_id
                WHERE inner_d.runid = :runId LABEL_VALUE_FILTER_PLACEHOLDER
                GROUP BY inner_d.id
                HAVING COUNT(*) = :filterKeysCount
            """;

    @Inject
    EntityManager em;

    protected FilterDef getFilterDef(JsonNode filter, Instant before, Instant after, boolean multiFilter, boolean byTest,
            Function<String, List<ExportedLabelValues>> checkFilter) {
        // byTest if true the datasets should be filtered by testId, otherwise by runId
        // filter sql query to be incorporated in the label_values query
        StringBuilder filterSqlBuilder = new StringBuilder();
        // filter object containing all simple key-value matches
        ObjectNode simpleObjectNode = null;
        // filter object containing multiFilter key-value matches
        ObjectNode multiFilterObjectNode = JsonNodeFactory.instance.objectNode();
        // list of keys we guessed need to be checked with multiFilter
        List<String> multiFilterKeys = new ArrayList<>();

        // filter argument check
        if (filter != null && filter.getNodeType() == JsonNodeType.OBJECT) {
            // if the provided filter is an object, the user is either (I) trying to filter by exact match key==value
            // or (II) trying to do a multiFiler check key==ANY(array of values)
            simpleObjectNode = (ObjectNode) filter;
            if (multiFilter) {
                // if multiFilter enabled and for every array value, I need to guess whether
                // it is an exact match or a multi filter check.
                // To do so, I will re-run the checkFilter (which is the labelValues call)
                // with that sub-object and if there are matches most likely means that the
                // key has an array as value so it is an exact match. Otherwise, when no
                // results are passed it most likely mean the user is trying a multiFilter
                simpleObjectNode.fields().forEachRemaining(e -> {
                    String key = e.getKey();
                    JsonNode value = e.getValue();
                    // if multiFilter makes sense only if the value is an array of possible values
                    if (value.getNodeType() == JsonNodeType.ARRAY) { //check if there are any matches
                        ObjectNode arrayFilter = new JsonNodeFactory(false).objectNode().set(key, value);
                        // this is going to execute the checkFilter which is the labelValues call with just this key-value
                        // as filter. This means that it is going to execute the whole logic just to check if no results
                        // are returned. In that case it means the user is most likely trying a multiFiler for this key-value
                        // and not an exact array match filter!!
                        // TODO: is there any other smart way to check this? especially without recomputing
                        // the whole labelValues logic
                        List<ExportedLabelValues> found = checkFilter.apply(arrayFilter.toString());
                        if (found.isEmpty()) {
                            multiFilterObjectNode.set(key, value);
                            multiFilterKeys.add(key);
                        }
                    }
                });

                // if assumeMulti has been populated, let's remove those objects from the original objectNode
                if (!multiFilterKeys.isEmpty()) {
                    // remove multiFilter keys from the original object node
                    simpleObjectNode.remove(multiFilterKeys);
                }
            }

            StringBuilder labelValueFilterPlaceholder = new StringBuilder();
            int keyCounter = 0;
            // check if there are EXACT match checks to be applied
            if (!simpleObjectNode.isEmpty()) {
                var fieldsIterator = simpleObjectNode.fields();
                while (fieldsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldsIterator.next();
                    if (!labelValueFilterPlaceholder.isEmpty()) {
                        labelValueFilterPlaceholder.append(OR_SEPARATOR);
                    }
                    // expecting params :keyN and :valueN where N is the ith element
                    labelValueFilterPlaceholder.append(" (inner_l.name = :key")
                            .append(keyCounter).append(" AND inner_lv.value = :value").append(keyCounter).append(") ");
                    keyCounter++;
                }
            }

            // check if there are MULTI_FILTER checks to be applied
            if (!multiFilterKeys.isEmpty()) {
                for (int i = keyCounter; i < multiFilterKeys.size() + keyCounter; i++) {
                    if (!labelValueFilterPlaceholder.isEmpty()) {
                        labelValueFilterPlaceholder.append(OR_SEPARATOR);
                    }
                    // expecting params :keyN and :valueN where N is the ith element
                    labelValueFilterPlaceholder.append(" (inner_l.name = :key")
                            .append(i).append(" AND inner_lv.value = ANY(:value").append(i).append(")) ");
                }
            }

            if (!labelValueFilterPlaceholder.isEmpty()) {
                // filterSql is for sure empty at this point
                filterSqlBuilder.append("datasetId IN (")
                        .append((byTest ? LABEL_VALUES_DATASETS_BY_TEST_AND_FILTER : LABEL_VALUES_DATASETS_BY_RUN_AND_FILTER)
                                .replace("LABEL_VALUE_FILTER_PLACEHOLDER",
                                        "AND (" + labelValueFilterPlaceholder + ")"))
                        .append(") ");
            }
        } else if (filter != null && filter.getNodeType() == JsonNodeType.STRING) {
            // the provided filter is just a jsonpath, so the check is to filter those labelValues for which
            // the jsonpath resolved to a NON-null value, i.e., it is populated!
            // TODO: check if working
            Util.CheckResult jsonpathResult = Util.castCheck(filter.asText(), "jsonpath", em);
            if (jsonpathResult.ok()) {
                // expecting param :filter
                filterSqlBuilder.append(LABEL_VALUES_FILTER_MATCHES_NOT_NULL);
            } else {
                throw new IllegalArgumentException(jsonpathResult.message());
            }
        }

        // before instant check
        if (before != null) {
            if (!filterSqlBuilder.isEmpty()) {
                filterSqlBuilder.append(FILTER_SEPARATOR);
            }
            // expecting param :before
            filterSqlBuilder.append(FILTER_BEFORE);
        }

        // after instant check
        if (after != null) {
            if (!filterSqlBuilder.isEmpty()) {
                filterSqlBuilder.append(FILTER_SEPARATOR);
            }
            // expecting param :after
            filterSqlBuilder.append(FILTER_AFTER);
        }

        String filterSql = "";
        if (!filterSqlBuilder.isEmpty()) {
            filterSql = FILTER_PREFIX + filterSqlBuilder;
        }

        return new FilterDef(filterSql, simpleObjectNode, multiFilterObjectNode, multiFilterKeys);
    }

    /**
     * Compute labelValues grouped by datasets for all runs under the provided test
     *
     * @return list of labelValues in the form of {@link ExportedLabelValues}
     */
    @Transactional
    public List<ExportedLabelValues> labelValuesByTest(int testId, String filter, String before, String after,
            boolean filtering, boolean metrics, String sort, String direction, Integer limit, int page, List<String> include,
            List<String> exclude, boolean multiFilter) {

        Instant beforeInstant = Util.toInstant(before);
        Instant afterInstant = Util.toInstant(after);

        FilterDef filterDef = getFilterDef(Util.getFilterObject(filter), beforeInstant, afterInstant, multiFilter, true,
                (str) -> labelValuesByTest(testId, str,
                        before, after, filtering, metrics, sort, direction, limit, page, include, exclude, false));

        ObjectNode simpleFilterObject = filterDef.simpleFilterObject();
        ObjectNode multiFilterObject = filterDef.multiFilterObject();
        String filterSql = filterDef.sql();

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
            Log.errorf("Filter SQL is empty but received not empty filter: %s", filter);
            //TODO there was an error with the filter, do we return that info to the user?
        }

        // --- ordering
        // by default order by runId
        String orderSql = LABEL_ORDER_PREFIX + "combined.runId DESC";
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

        // --- limit
        String limitSql = "";
        if (limit != null) {
            limitSql = "limit " + limit + " offset " + limit * Math.max(0, page);
        }
        String sql = LABEL_VALUES_QUERY_BY_TEST
                .replace("FILTER_PLACEHOLDER", filterSql)
                .replace("INCLUDE_EXCLUDE_PLACEHOLDER", includeExcludeSql)
                .replace("ORDER_PLACEHOLDER", orderSql)
                .replace("LIMIT_PLACEHOLDER", limitSql);

        NativeQuery<Object[]> query = (NativeQuery<Object[]>) (em.createNativeQuery(sql))
                .setParameter("testId", testId)
                .setParameter("filteringLabels", filtering)
                .setParameter("metricLabels", metrics);

        // checks whether we have to add query filtering
        // if so we need to add all parameters bindings
        if (!filterSql.isEmpty()) {
            int filterKeysCounter = 0;
            if (simpleFilterObject != null && !simpleFilterObject.isEmpty()) {
                Iterator<Map.Entry<String, JsonNode>> fieldsIterator = simpleFilterObject.fields();
                while (fieldsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldsIterator.next();
                    query.setParameter("key" + filterKeysCounter, entry.getKey());
                    query.setParameter("value" + filterKeysCounter, entry.getValue(), JsonBinaryType.INSTANCE);
                    filterKeysCounter++;
                }
            }

            if (multiFilterObject != null && !multiFilterObject.isEmpty()) {
                Iterator<Map.Entry<String, JsonNode>> fieldsIterator = multiFilterObject.fields();
                while (fieldsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldsIterator.next();
                    query.setParameter("key" + filterKeysCounter, entry.getKey());
                    query.setParameter("value" + filterKeysCounter, entry.getValue(), JsonbSetType.INSTANCE);
                    filterKeysCounter++;
                }
            }

            if (beforeInstant != null) {
                query.setParameter("before", beforeInstant, StandardBasicTypes.INSTANT);
            }

            if (afterInstant != null) {
                query.setParameter("after", afterInstant, StandardBasicTypes.INSTANT);
            }

            if (filterSql.contains(LABEL_VALUES_FILTER_MATCHES_NOT_NULL)) {
                // TODO: check if this is working!!!!
                query.setParameter("filter", filter);
            }

            if ((multiFilterObject != null && !multiFilterObject.isEmpty())
                    || (simpleFilterObject != null && !simpleFilterObject.isEmpty())) {
                query.setParameter("filterKeysCount", filterKeysCounter);
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

        // add query result types
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
     * This is nearly identical to the {@link LabelValuesService#labelValuesByTest}, the main difference
     * is that this is going to return results for a specific run and not for all the runs under a test.
     *
     * @return list of labelValues in the form of {@link ExportedLabelValues}
     */
    @Transactional
    public List<ExportedLabelValues> labelValuesByRun(int runId, String filter, String sort, String direction, int limit,
            int page,
            List<String> include, List<String> exclude, boolean multiFilter) {

        FilterDef filterDef = getFilterDef(Util.getFilterObject(filter), null, null, multiFilter, false,
                (str) -> labelValuesByRun(runId, str, sort, direction, limit, page, include, exclude, false));

        ObjectNode simpleFilterObject = filterDef.simpleFilterObject();
        ObjectNode multiFilterObject = filterDef.multiFilterObject();
        String filterSql = filterDef.sql();

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
            Log.errorf("Filter SQL is empty but received not empty filter: %s", filter);
            //TODO there was an error with the filter, do we return that info to the user?
        }

        // --- ordering
        String orderSql = "";

        String orderDirection = direction.equalsIgnoreCase("ascending") ? "ASC" : "DESC";
        if (!sort.isBlank()) {
            Util.CheckResult jsonpathResult = Util.castCheck(sort, "jsonpath", em);
            if (jsonpathResult.ok()) {
                orderSql = "order by jsonb_path_query(combined.values,CAST( :orderBy as jsonpath)) " + orderDirection
                        + ", combined.datasetId DESC";
            } else {
                orderSql = "order by combined.datasetId DESC";
            }
        }

        // --- limit
        String limitSql = "limit " + limit + " offset " + limit * Math.max(0, page);

        String sql = LABEL_VALUES_QUERY_BY_RUN
                .replace("FILTER_PLACEHOLDER", filterSql)
                .replace("INCLUDE_EXCLUDE_PLACEHOLDER", includeExcludeSql)
                .replace("ORDER_PLACEHOLDER", orderSql)
                .replace("LIMIT_PLACEHOLDER", limitSql);

        NativeQuery<Object[]> query = (NativeQuery<Object[]>) (em.createNativeQuery(sql))
                .setParameter("runId", runId);

        // checks whether we have to add query filtering
        // if so we need to add all parameters bindings
        if (!filterSql.isEmpty()) {
            int filterKeysCounter = 0;
            if (simpleFilterObject != null && !simpleFilterObject.isEmpty()) {
                Iterator<Map.Entry<String, JsonNode>> fieldsIterator = simpleFilterObject.fields();
                while (fieldsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldsIterator.next();
                    query.setParameter("key" + filterKeysCounter, entry.getKey());
                    query.setParameter("value" + filterKeysCounter, entry.getValue(), JsonBinaryType.INSTANCE);
                    filterKeysCounter++;
                }
            }

            if (multiFilterObject != null && !multiFilterObject.isEmpty()) {
                Iterator<Map.Entry<String, JsonNode>> fieldsIterator = multiFilterObject.fields();
                while (fieldsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldsIterator.next();
                    query.setParameter("key" + filterKeysCounter, entry.getKey());
                    query.setParameter("value" + filterKeysCounter, entry.getValue(), JsonbSetType.INSTANCE);
                    filterKeysCounter++;
                }
            }

            if (filterSql.contains(LABEL_VALUES_FILTER_MATCHES_NOT_NULL)) {
                // TODO: check if this is working!!!!
                query.setParameter("filter", filter);
            }

            if ((multiFilterObject != null && !multiFilterObject.isEmpty())
                    || (simpleFilterObject != null && !simpleFilterObject.isEmpty())) {
                query.setParameter("filterKeysCount", filterKeysCounter);
            }
        }

        if (includeExcludeSql.contains(":include")) {
            query.setParameter("include", mutableInclude);
        } else if (includeExcludeSql.contains(":exclude")) {
            query.setParameter("exclude", exclude);
        }

        if (orderSql.contains(":orderBy")) {
            query.setParameter("orderBy", sort);
        }

        // add query result types
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
     * @param sql SQL string that represents the labelValues filter
     * @param simpleFilterObject
     * @param multiFilterObject
     * @param multiFilterKeys filter keys for which we perform a multiFilter check
     */
    protected record FilterDef(String sql, ObjectNode simpleFilterObject, ObjectNode multiFilterObject,
            List<String> multiFilterKeys) {
    }
}
