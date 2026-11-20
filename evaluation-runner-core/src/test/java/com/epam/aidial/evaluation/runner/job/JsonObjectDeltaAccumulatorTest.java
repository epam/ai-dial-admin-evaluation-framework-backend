package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("JsonObjectDeltaAccumulator")
class JsonObjectDeltaAccumulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should recursively merge properties and append arrays including duplicates and nulls")
    void accumulate_append_recursivelyMerges() {
        final JsonObjectDeltaAccumulator accumulator = new JsonObjectDeltaAccumulator(ArrayMergeMode.APPEND);
        accumulator.accumulate(
                node(
                        "{\"label\":\"first\",\"metadata\":{\"count\":1,\"tags\":[\"one\"]},\"annotations\":[{\"id\":\"a\",\"label\":null}]}"));
        accumulator.accumulate(
                node(
                        "{\"label\":\"second\",\"metadata\":{\"tags\":[\"two\"],\"source\":\"synthetic\"},\"annotations\":[{\"id\":\"a\",\"label\":null},null,{\"id\":\"b\"}]}"));
        accumulator.accumulate(node("{\"label\":null,\"annotations\":[]}"));

        assertThat(accumulator.getMerged()).isEqualTo(node("""
                {
                  "label": "second",
                  "metadata": {"count": 1, "tags": ["one", "two"], "source": "synthetic"},
                  "annotations": [
                    {"id": "a", "label": null},
                    {"id": "a", "label": null},
                    null,
                    {"id": "b"}
                  ]
                }
                """));
    }

    @Test
    @DisplayName("Should replace arrays recursively without replacing sibling properties")
    void accumulate_replace_replacesArraysIncludingEmpty() {
        final JsonObjectDeltaAccumulator accumulator = new JsonObjectDeltaAccumulator(ArrayMergeMode.REPLACE);
        accumulator.accumulate(node("{\"items\":[1],\"metadata\":{\"label\":\"kept\",\"tags\":[\"one\"]}}"));
        accumulator.accumulate(node("{\"items\":[2],\"metadata\":{\"tags\":[\"two\"]}}"));
        assertThat(accumulator.getMerged())
                .isEqualTo(node("{\"items\":[2],\"metadata\":{\"label\":\"kept\",\"tags\":[\"two\"]}}"));

        accumulator.accumulate(node("{\"items\":[],\"metadata\":{\"tags\":[]}}"));
        assertThat(accumulator.getMerged())
                .isEqualTo(node("{\"items\":[],\"metadata\":{\"label\":\"kept\",\"tags\":[]}}"));
    }

    @Test
    @DisplayName("Should ignore object null properties recursively in fresh and replacement objects")
    void accumulate_freshAndReplacedObjects_ignoreNullProperties() {
        final JsonObjectDeltaAccumulator accumulator = new JsonObjectDeltaAccumulator(ArrayMergeMode.APPEND);
        accumulator.accumulate(node(
                "{\"metadata\":{\"label\":null,\"nested\":{\"missing\":null}},\"detail\":\"old\",\"ignored\":null}"));
        assertThat(accumulator.getMerged()).isEqualTo(node("{\"metadata\":{\"nested\":{}},\"detail\":\"old\"}"));

        accumulator.accumulate(node("{\"detail\":{\"label\":null},\"metadata\":{\"newObject\":{\"missing\":null}}}"));
        assertThat(accumulator.getMerged())
                .isEqualTo(node("{\"metadata\":{\"nested\":{},\"newObject\":{}},\"detail\":{}}"));
    }

    @Test
    @DisplayName("Should replace values when their JSON types change")
    void accumulate_typeChanges_replacePreviousValues() {
        final JsonObjectDeltaAccumulator accumulator = new JsonObjectDeltaAccumulator(ArrayMergeMode.APPEND);
        accumulator.accumulate(node("{\"detail\":{\"old\":1},\"other\":[1],\"third\":false}"));
        accumulator.accumulate(node("{\"detail\":[\"new\"],\"other\":3,\"third\":{\"new\":true}}"));

        assertThat(accumulator.getMerged())
                .isEqualTo(node("{\"detail\":[\"new\"],\"other\":3,\"third\":{\"new\":true}}"));
    }

    @Test
    @DisplayName("Should ignore invalid empty and null-only input and retain supplied empty arrays")
    void accumulate_invalidAndEmptyInput_onlyEmptyArraysContribute() {
        final JsonObjectDeltaAccumulator accumulator = new JsonObjectDeltaAccumulator(ArrayMergeMode.APPEND);
        accumulator.accumulate(null);
        accumulator.accumulate(node("null"));
        accumulator.accumulate(node("[]"));
        accumulator.accumulate(node("{}"));
        accumulator.accumulate(node("{\"ignored\":null}"));
        assertThat(accumulator.getMerged()).isNull();

        accumulator.accumulate(node("{\"items\":[]}"));
        assertThat(accumulator.getMerged()).isEqualTo(node("{\"items\":[]}"));
    }

    @Test
    @DisplayName("Should isolate inputs and returned snapshots from subsequent mutations")
    void accumulate_sourceAndSnapshotMutations_areIsolated() {
        final JsonObjectDeltaAccumulator accumulator = new JsonObjectDeltaAccumulator(ArrayMergeMode.APPEND);
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

    private JsonNode node(String json) {
        return OBJECT_MAPPER.readTree(json);
    }
}
