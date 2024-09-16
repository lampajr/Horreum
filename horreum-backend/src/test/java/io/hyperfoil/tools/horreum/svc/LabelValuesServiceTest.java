package io.hyperfoil.tools.horreum.svc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.hyperfoil.tools.horreum.test.HorreumTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(HorreumTestProfile.class)
class LabelValuesServiceTest extends BaseServiceNoRestTest {

    @Inject
    LabelValuesService labelValuesService;

    @Test
    void testGetFilterDefFromJsonpath() {
        LabelValuesService.FilterDef filterDef = labelValuesService.getFilterDef(
                JsonNodeFactory.instance.textNode("$.field"),
                null,
                null,
                false,
                s -> null);

        assertEquals("WHERE combined.value @\\?\\? CAST( :filter as jsonpath)", filterDef.sql());
        assertNull(filterDef.filterObject());
        assertEquals(1, filterDef.names().size());
        assertArrayEquals(new String[] { "filter" }, filterDef.names().toArray());
        assertEquals(0, filterDef.multis().size());
    }

    @Test
    void testGetFilterDefWithBefore() {
        LabelValuesService.FilterDef filterDef = labelValuesService.getFilterDef(
                JsonNodeFactory.instance.textNode("$.field"),
                Instant.now(),
                null,
                false,
                s -> null);

        assertEquals("WHERE combined.value @\\?\\? CAST( :filter as jsonpath) AND  combined.stop < :before", filterDef.sql());
        assertNull(filterDef.filterObject());
        assertEquals(2, filterDef.names().size());
        assertArrayEquals(new String[] { "filter", "before" }, filterDef.names().toArray());
        assertEquals(0, filterDef.multis().size());
    }

    @Test
    void testGetFilterDefWithAfter() {
        LabelValuesService.FilterDef filterDef = labelValuesService.getFilterDef(
                JsonNodeFactory.instance.textNode("$.field"),
                null,
                Instant.now(),
                false,
                s -> null);

        assertEquals("WHERE combined.value @\\?\\? CAST( :filter as jsonpath) AND  combined.start > :after", filterDef.sql());
        assertNull(filterDef.filterObject());
        assertEquals(2, filterDef.names().size());
        assertArrayEquals(new String[] { "filter", "after" }, filterDef.names().toArray());
        assertEquals(0, filterDef.multis().size());
    }

    @Test
    void testGetFilterDefWithBeforeAndAfter() {
        LabelValuesService.FilterDef filterDef = labelValuesService.getFilterDef(
                JsonNodeFactory.instance.textNode("$.field"),
                Instant.now(),
                Instant.now(),
                false,
                s -> null);

        assertEquals(
                "WHERE combined.value @\\?\\? CAST( :filter as jsonpath) AND  combined.stop < :before AND  combined.start > :after",
                filterDef.sql());
        assertNull(filterDef.filterObject());
        assertEquals(3, filterDef.names().size());
        assertArrayEquals(new String[] { "filter", "before", "after" }, filterDef.names().toArray());
        assertEquals(0, filterDef.multis().size());
    }

    @Test
    void testGetFilterDefFromObject() {
        LabelValuesService.FilterDef filterDef = labelValuesService.getFilterDef(
                JsonNodeFactory.instance.objectNode().put("key", "value"),
                null,
                null,
                false,
                s -> null);

        assertEquals("WHERE combined.value @> :filter", filterDef.sql());
        assertEquals(JsonNodeType.OBJECT, filterDef.filterObject().getNodeType());
        assertEquals("value", filterDef.filterObject().get("key").asText());
        assertEquals(1, filterDef.names().size());
        assertArrayEquals(new String[] { "filter" }, filterDef.names().toArray());
        assertEquals(0, filterDef.multis().size());
    }

    @Test
    void testGetFilterDefFromObjectWithMultiFilter() {
        ObjectNode filter = JsonNodeFactory.instance.objectNode().put("key1", "value1");
        filter.set("key2", JsonNodeFactory.instance.arrayNode().add("possible1").add("possible2"));
        filter.set("key3", JsonNodeFactory.instance.arrayNode().add("string").add(3));

        LabelValuesService.FilterDef filterDef = labelValuesService.getFilterDef(
                filter,
                null,
                null,
                true,
                s -> List.of());

        assertEquals(
                "WHERE combined.value @> :filter AND  jsonb_path_query_first(combined.value,CAST( :key0 as jsonpath)) = ANY(:value0)  AND  jsonb_path_query_first(combined.value,CAST( :key1 as jsonpath)) = ANY(:value1) ",
                filterDef.sql());
        assertEquals(JsonNodeType.OBJECT, filterDef.filterObject().getNodeType());
        assertEquals("value1", filterDef.filterObject().get("key1").asText());
        assertNull(filterDef.filterObject().get("key2"));
        assertNull(filterDef.filterObject().get("key3"));
        assertEquals(5, filterDef.names().size());
        assertArrayEquals(new String[] { "filter", "key1", "key0", "value1", "value0" }, filterDef.names().toArray());
        assertEquals(2, filterDef.multis().size());
        assertArrayEquals(new String[] { "key2", "key3" }, filterDef.multis().toArray());
    }
}
