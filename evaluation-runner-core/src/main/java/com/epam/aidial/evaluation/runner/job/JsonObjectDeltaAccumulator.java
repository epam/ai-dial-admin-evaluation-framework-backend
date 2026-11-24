package com.epam.aidial.evaluation.runner.job;

import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.ANNOTATIONS;

import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Merges JSON object deltas and returns defensive snapshots. */
final class JsonObjectDeltaAccumulator {

    private final ArrayMergeMode arrayMode;
    private final ObjectNode merged = JsonNodeFactory.instance.objectNode();

    JsonObjectDeltaAccumulator(ArrayMergeMode arrayMode) {
        this.arrayMode = Objects.requireNonNull(arrayMode);
    }

    void accumulate(JsonNode delta) {
        if (delta != null && delta.isObject()) {
            mergeInto(merged, delta, arrayMode, false);
        }
    }

    void accumulateWithAnnotations(JsonNode delta) {
        if (delta == null || !delta.isObject()) {
            return;
        }
        for (final Map.Entry<String, JsonNode> entry : delta.properties()) {
            final String key = entry.getKey();
            final JsonNode value = entry.getValue();
            if (arrayMode == ArrayMergeMode.APPEND && ANNOTATIONS.equals(key) && value.isArray()) {
                final ArrayNode annotations =
                        merged.get(key) instanceof ArrayNode array ? array : JsonNodeFactory.instance.arrayNode();
                AnnotationDeltaAccumulator.merge(annotations, value);
                merged.set(key, annotations);
            } else {
                final ObjectNode single = JsonNodeFactory.instance.objectNode();
                single.set(key, value);
                mergeInto(merged, single, arrayMode, false);
            }
        }
    }

    ObjectNode getMerged() {
        return merged.isEmpty() ? null : merged.deepCopy();
    }

    /** Legacy custom-content copies newly introduced objects, including their null properties. */
    static void mergePreservingNewObjects(ObjectNode target, JsonNode source) {
        mergeInto(target, source, ArrayMergeMode.REPLACE, true);
    }

    private static void mergeInto(
            ObjectNode target, JsonNode source, ArrayMergeMode arrayMode, boolean preserveNewObjects) {
        for (final Map.Entry<String, JsonNode> entry : source.properties()) {
            final String key = entry.getKey();
            final JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            final JsonNode existing = target.get(key);
            if (value.isObject()) {
                if (existing instanceof ObjectNode existingObject) {
                    mergeInto(existingObject, value, arrayMode, preserveNewObjects);
                } else if (preserveNewObjects) {
                    target.set(key, value.deepCopy());
                } else {
                    final ObjectNode newObject = JsonNodeFactory.instance.objectNode();
                    mergeInto(newObject, value, arrayMode, false);
                    target.set(key, newObject);
                }
            } else if (value.isArray()
                    && existing instanceof ArrayNode existingArray
                    && arrayMode == ArrayMergeMode.APPEND) {
                for (final JsonNode element : value) {
                    existingArray.add(element.deepCopy());
                }
            } else {
                target.set(key, value.deepCopy());
            }
        }
    }
}
