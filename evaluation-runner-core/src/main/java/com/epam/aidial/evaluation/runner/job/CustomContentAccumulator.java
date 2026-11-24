package com.epam.aidial.evaluation.runner.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Accumulates custom-content deltas using scoped policies for stages, annotations and snapshots. */
final class CustomContentAccumulator {

    private static final String ATTACHMENTS = "attachments";
    private static final String STAGES = "stages";
    private static final String ANNOTATIONS = "annotations";
    private static final String INDEX = "index";
    private final ObjectNode merged = JsonNodeFactory.instance.objectNode();

    void accumulate(JsonNode customContent) {
        if (customContent == null || !customContent.isObject()) {
            return;
        }
        for (final Map.Entry<String, JsonNode> entry : customContent.properties()) {
            final String key = entry.getKey();
            final JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if ((STAGES.equals(key) || ATTACHMENTS.equals(key)) && value.isArray()) {
                mergeIndexedField(merged, key, value, STAGES.equals(key));
            } else if (ANNOTATIONS.equals(key) && value.isArray()) {
                final ArrayNode annotations =
                        merged.get(key) instanceof ArrayNode array ? array : JsonNodeFactory.instance.arrayNode();
                AnnotationDeltaAccumulator.merge(annotations, value);
                merged.set(key, annotations);
            } else if (("state".equals(key) || "form_schema".equals(key)) && value.isObject()) {
                merged.set(key, value.deepCopy());
            } else {
                mergeGenericField(merged, key, value);
            }
        }
    }

    ObjectNode getMerged() {
        return merged.isEmpty() ? null : merged.deepCopy();
    }

    private static void mergeIndexedField(ObjectNode parent, String key, JsonNode incoming, boolean stage) {
        final JsonNode previous = parent.get(key);
        final TreeMap<Integer, ObjectNode> indexed = new TreeMap<>();
        final List<ObjectNode> unindexed = new ArrayList<>();
        if (previous instanceof ArrayNode array) {
            for (final JsonNode element : array) {
                addIndexed(element, indexed, unindexed, stage, false);
            }
        }
        for (final JsonNode element : incoming) {
            addIndexed(element, indexed, unindexed, stage, true);
        }
        if (indexed.isEmpty() && unindexed.isEmpty() && previous == null) {
            return;
        }
        final ArrayNode result = JsonNodeFactory.instance.arrayNode();
        indexed.values().forEach(result::add);
        unindexed.forEach(result::add);
        parent.set(key, result);
    }

    private static void addIndexed(
            JsonNode element,
            TreeMap<Integer, ObjectNode> indexed,
            List<ObjectNode> unindexed,
            boolean stage,
            boolean incoming) {
        if (!(element instanceof ObjectNode object)) {
            return;
        }
        final JsonNode index = object.get(INDEX);
        final ObjectNode existing = index != null && index.isNumber() ? indexed.get(index.asInt()) : null;
        if (incoming && existing != null) {
            if (stage) {
                mergeStage(existing, object);
            } else {
                JsonObjectDeltaAccumulator.mergePreservingNewObjects(existing, object);
            }
            return;
        }
        final ObjectNode copy = object.deepCopy();
        if (incoming && stage) {
            removeEmptyText(copy, "name", "content");
            final JsonNode attachments = copy.get(ATTACHMENTS);
            if (attachments != null && attachments.isArray()) {
                copy.remove(ATTACHMENTS);
                mergeStageAttachments(copy, attachments);
                if (!copy.has(ATTACHMENTS)) {
                    copy.set(ATTACHMENTS, JsonNodeFactory.instance.arrayNode());
                }
            }
        }
        if (index == null || !index.isNumber()) {
            unindexed.add(copy);
        } else {
            indexed.put(index.asInt(), copy);
        }
    }

    private static void mergeStage(ObjectNode target, ObjectNode source) {
        for (final Map.Entry<String, JsonNode> entry : source.properties()) {
            final String key = entry.getKey();
            final JsonNode value = entry.getValue();
            if ("name".equals(key) || "content".equals(key)) {
                mergeText(target, key, value);
            } else if (ATTACHMENTS.equals(key) && value != null && value.isArray()) {
                mergeStageAttachments(target, value);
            } else {
                mergeGenericField(target, key, value);
            }
        }
    }

    private static void mergeStageAttachments(ObjectNode stage, JsonNode incoming) {
        final JsonNode previous = stage.get(ATTACHMENTS);
        final TreeMap<Integer, ObjectNode> indexed = new TreeMap<>();
        final List<ObjectNode> unindexed = new ArrayList<>();
        if (previous instanceof ArrayNode array) {
            for (final JsonNode element : array) {
                addIndexed(element, indexed, unindexed, false, false);
            }
        }
        for (final JsonNode element : incoming) {
            if (!(element instanceof ObjectNode object)) {
                continue;
            }
            final JsonNode index = object.get(INDEX);
            final ObjectNode existing = index != null && index.isNumber() ? indexed.get(index.asInt()) : null;
            if (existing == null) {
                final ObjectNode copy = object.deepCopy();
                removeEmptyText(copy, "title", "data");
                if (index != null && index.isNumber()) {
                    indexed.put(index.asInt(), copy);
                } else {
                    unindexed.add(copy);
                }
            } else {
                for (final Map.Entry<String, JsonNode> entry : object.properties()) {
                    final String key = entry.getKey();
                    if ("title".equals(key) || "data".equals(key)) {
                        mergeText(existing, key, entry.getValue());
                    } else {
                        mergeGenericField(existing, key, entry.getValue());
                    }
                }
            }
        }
        if (indexed.isEmpty() && unindexed.isEmpty() && previous == null) {
            return;
        }
        final ArrayNode result = JsonNodeFactory.instance.arrayNode();
        indexed.values().forEach(result::add);
        unindexed.forEach(result::add);
        stage.set(ATTACHMENTS, result);
    }

    private static void removeEmptyText(ObjectNode object, String... keys) {
        for (final String key : keys) {
            final JsonNode value = object.get(key);
            if (value != null
                    && (value.isNull() || (value.isString() && value.asString().isEmpty()))) {
                object.remove(key);
            }
        }
    }

    private static void mergeText(ObjectNode target, String key, JsonNode value) {
        if (value == null
                || value.isNull()
                || (value.isString() && value.asString().isEmpty())) {
            return;
        }
        final JsonNode existing = target.get(key);
        if (value.isString() && existing != null && existing.isString()) {
            target.put(key, existing.asString() + value.asString());
        } else {
            mergeGenericField(target, key, value);
        }
    }

    private static void mergeGenericField(ObjectNode target, String key, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        final ObjectNode single = JsonNodeFactory.instance.objectNode();
        single.set(key, value);
        JsonObjectDeltaAccumulator.mergePreservingNewObjects(target, single);
    }
}
