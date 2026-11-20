package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("CustomFieldsAccumulator")
class CustomFieldsAccumulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should recursively merge objects and keep the latest non-null scalars")
    void accumulate_objectsAndScalars_mergesRecursively() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        accumulator.accumulate(node("{\"metadata\":{\"label\":\"first\",\"count\":1},\"status\":\"old\"}"));
        accumulator.accumulate(node("{\"metadata\":{\"label\":\"second\",\"source\":\"synthetic\"},\"status\":null}"));

        assertThat(accumulator.getMerged())
                .isEqualTo(
                        node(
                                "{\"metadata\":{\"label\":\"second\",\"count\":1,\"source\":\"synthetic\"},\"status\":\"old\"}"));
    }

    @Test
    @DisplayName("Should append nested and top-level arrays without deduplicating or normalizing elements")
    void accumulate_defaultMode_appendsArrays() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        accumulator.accumulate(
                node("{\"metadata\":{\"tags\":[\"one\"]},\"annotations\":[{\"id\":\"a\",\"label\":null}]}"));
        accumulator.accumulate(
                node(
                        "{\"metadata\":{\"tags\":[\"two\"]},\"annotations\":[{\"id\":\"a\",\"label\":null},null,{\"id\":\"b\"}]}"));
        accumulator.accumulate(node("{\"metadata\":{\"tags\":[]},\"annotations\":[]}"));

        assertThat(accumulator.getMerged())
                .isEqualTo(
                        node(
                                "{\"metadata\":{\"tags\":[\"one\",\"two\"]},\"annotations\":[{\"id\":\"a\",\"label\":null},{\"id\":\"a\",\"label\":null},null,{\"id\":\"b\"}]}"));
    }

    @Test
    @DisplayName("Should omit absent null non-object empty and null-only extensions")
    void accumulate_noFields_returnsNull() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        assertThat(accumulator.getMerged()).isNull();
        accumulator.accumulate(null);
        accumulator.accumulate(node("null"));
        accumulator.accumulate(node("[]"));
        accumulator.accumulate(node("\"value\""));
        accumulator.accumulate(node("42"));
        accumulator.accumulate(node("true"));
        accumulator.accumulate(node("{}"));
        accumulator.accumulate(node("{\"ignored\":null}"));

        assertThat(accumulator.getMerged()).isNull();
    }

    @Test
    @DisplayName("Should preserve a supplied empty array as a field")
    void accumulate_initialEmptyArray_retainsField() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        accumulator.accumulate(node("{\"annotations\":[]}"));

        assertThat(accumulator.getMerged()).isEqualTo(node("{\"annotations\":[]}"));
    }

    @Test
    @DisplayName("Should retain fresh nested objects after recursively ignoring null properties")
    void accumulate_freshNullOnlyObject_retainsEmptyObject() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        accumulator.accumulate(node("{\"metadata\":{\"label\":null,\"nested\":{\"missing\":null}}}"));

        assertThat(accumulator.getMerged()).isEqualTo(node("{\"metadata\":{\"nested\":{}}}"));
    }

    @Test
    @DisplayName("Should replace a scalar with an object emptied by null filtering")
    void accumulate_nullOnlyObject_replacesScalar() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        accumulator.accumulate(node("{\"metadata\":\"old\"}"));
        accumulator.accumulate(node("{\"metadata\":{\"label\":null}}"));

        assertThat(accumulator.getMerged()).isEqualTo(node("{\"metadata\":{}}"));
    }

    @Test
    @DisplayName("Should replace existing values on non-null type changes")
    void accumulate_typeChanges_replaceValues() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        accumulator.accumulate(node("{\"detail\":{\"label\":\"old\"},\"count\":[1]}"));
        accumulator.accumulate(node("{\"detail\":[\"new\"],\"count\":3}"));

        assertThat(accumulator.getMerged()).isEqualTo(node("{\"detail\":[\"new\"],\"count\":3}"));
    }

    @Test
    @DisplayName("Should isolate source objects and returned snapshots from accumulated state")
    void accumulate_sourceAndResultMutations_areIsolated() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
        final ObjectNode first =
                (ObjectNode) node("{\"metadata\":{\"name\":\"original\"},\"items\":[{\"label\":\"first\"}]}");
        final ObjectNode second =
                (ObjectNode) node("{\"metadata\":{\"other\":\"value\"},\"items\":[{\"label\":\"second\"}]}");
        final JsonNode firstBefore = first.deepCopy();
        final JsonNode secondBefore = second.deepCopy();
        accumulator.accumulate(first);
        accumulator.accumulate(second);
        assertThat(first).isEqualTo(firstBefore);
        assertThat(second).isEqualTo(secondBefore);

        ((ObjectNode) first.get("metadata")).put("name", "changed");
        ((ObjectNode) second.get("items").get(0)).put("label", "changed");
        final ObjectNode snapshot = accumulator.getMerged();
        ((ObjectNode) snapshot.get("metadata")).put("other", "changed");
        ((ObjectNode) snapshot.get("items").get(0)).put("label", "changed");
        assertThat(accumulator.getMerged())
                .isEqualTo(
                        node(
                                "{\"metadata\":{\"name\":\"original\",\"other\":\"value\"},\"items\":[{\"label\":\"first\"},{\"label\":\"second\"}]}"));
    }

    @Test
    @DisplayName("Should replace arrays and preserve sibling fields in nested objects")
    void accumulate_replaceMode_replacesArraysRecursively() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator(ArrayMergeMode.REPLACE);
        accumulator.accumulate(node("{\"annotations\":[1],\"metadata\":{\"label\":\"retained\",\"tags\":[\"one\"]}}"));
        accumulator.accumulate(node("{\"annotations\":[2],\"metadata\":{\"tags\":[\"two\"]}}"));

        assertThat(accumulator.getMerged())
                .isEqualTo(node("{\"annotations\":[2],\"metadata\":{\"label\":\"retained\",\"tags\":[\"two\"]}}"));
    }

    @Test
    @DisplayName("Should clear top-level and nested arrays when replacement arrays are empty")
    void accumulate_replaceMode_emptyArraysClear() {
        final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator(ArrayMergeMode.REPLACE);
        accumulator.accumulate(node("{\"annotations\":[1],\"metadata\":{\"label\":\"retained\",\"tags\":[\"one\"]}}"));
        accumulator.accumulate(node("{\"annotations\":[],\"metadata\":{\"tags\":[]}}"));

        assertThat(accumulator.getMerged())
                .isEqualTo(node("{\"annotations\":[],\"metadata\":{\"label\":\"retained\",\"tags\":[]}}"));
    }

    private JsonNode node(String json) {
        return OBJECT_MAPPER.readTree(json);
    }
}
