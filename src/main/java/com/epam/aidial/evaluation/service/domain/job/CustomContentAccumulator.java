package com.epam.aidial.evaluation.service.domain.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accumulates DIAL's {@code custom_content} extension field across incremental OpenAI-mode SSE delta
 * chunks ({@code choices[0].delta.custom_content}). Not a Spring bean — instantiate a fresh instance per
 * accumulation run alongside {@link StreamingResponseAccumulator}.
 *
 * <p>Merge semantics applied as each delta's {@code custom_content} object is fed in via {@link
 * #accumulate(JsonNode)}:
 *
 * <ul>
 *   <li><b>Scalar top-level fields</b> (and object-valued fields other than {@code attachments}/{@code
 *       stages}): last non-null value wins. A {@code null} (or absent) value never erases a previously
 *       accumulated value. An object value is deep-merged field-by-field into the previously accumulated
 *       object at that key, so a later partial cannot blank out fields the earlier partial supplied.
 *   <li><b>{@code attachments} and {@code stages} arrays</b>: never replaced wholesale. Each element is
 *       keyed by its {@code index} field; a later partial element carrying the same {@code index} is
 *       deep-merged into the previously accumulated element at that index (fields present only in the
 *       earlier partial are preserved; fields present in the later partial overwrite). Output order is
 *       ascending by {@code index}. An element with a missing or non-numeric {@code index} is never
 *       merged with anything — it is appended, in receipt order, after all indexed elements.
 * </ul>
 */
final class CustomContentAccumulator {

    private static final String ATTACHMENTS_FIELD = "attachments";
    private static final String STAGES_FIELD = "stages";
    private static final String INDEX_FIELD = "index";

    private final JsonNodeFactory nodeFactory = JsonNodeFactory.instance;

    private ObjectNode scalarsAndObjects;
    private final TreeMap<Integer, ObjectNode> attachmentsByIndex = new TreeMap<>();
    private final List<ObjectNode> unindexedAttachments = new ArrayList<>();
    private final TreeMap<Integer, ObjectNode> stagesByIndex = new TreeMap<>();
    private final List<ObjectNode> unindexedStages = new ArrayList<>();

    /**
     * Feeds one delta's {@code custom_content} node. No-op when {@code customContent} is {@code null} or
     * not a JSON object (most chunks carry no {@code custom_content} at all).
     */
    void accumulate(JsonNode customContent) {
        if (customContent == null || !customContent.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : customContent.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (ATTACHMENTS_FIELD.equals(key)) {
                mergeIndexedArray(value, attachmentsByIndex, unindexedAttachments);
            } else if (STAGES_FIELD.equals(key)) {
                mergeIndexedArray(value, stagesByIndex, unindexedStages);
            } else {
                mergeScalarOrObject(key, value);
            }
        }
    }

    /**
     * Returns the merged {@code custom_content} object, or {@code null} when nothing has been
     * accumulated (so callers can skip attaching it entirely).
     */
    ObjectNode getMerged() {
        boolean hasScalarFields = scalarsAndObjects != null && !scalarsAndObjects.isEmpty();
        boolean hasAttachments = !attachmentsByIndex.isEmpty() || !unindexedAttachments.isEmpty();
        boolean hasStages = !stagesByIndex.isEmpty() || !unindexedStages.isEmpty();
        if (!hasScalarFields && !hasAttachments && !hasStages) {
            return null;
        }

        ObjectNode result = scalarsAndObjects != null ? scalarsAndObjects.deepCopy() : nodeFactory.objectNode();
        if (hasAttachments) {
            result.set(ATTACHMENTS_FIELD, buildArray(attachmentsByIndex, unindexedAttachments));
        }
        if (hasStages) {
            result.set(STAGES_FIELD, buildArray(stagesByIndex, unindexedStages));
        }
        return result;
    }

    private void mergeScalarOrObject(String key, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (scalarsAndObjects == null) {
            scalarsAndObjects = nodeFactory.objectNode();
        }
        JsonNode existing = scalarsAndObjects.get(key);
        if (value.isObject() && existing instanceof ObjectNode existingObject) {
            deepMergeInto(existingObject, value);
        } else {
            scalarsAndObjects.set(key, value.deepCopy());
        }
    }

    private void mergeIndexedArray(
            JsonNode incomingArray, TreeMap<Integer, ObjectNode> byIndex, List<ObjectNode> unindexed) {
        if (incomingArray == null || !incomingArray.isArray()) {
            return;
        }
        for (JsonNode element : incomingArray) {
            if (element == null || !element.isObject()) {
                continue;
            }
            ObjectNode elementCopy = (ObjectNode) element.deepCopy();
            JsonNode indexNode = element.get(INDEX_FIELD);
            if (indexNode == null || !indexNode.isNumber()) {
                unindexed.add(elementCopy);
                continue;
            }
            int index = indexNode.asInt();
            ObjectNode existing = byIndex.get(index);
            if (existing == null) {
                byIndex.put(index, elementCopy);
            } else {
                deepMergeInto(existing, element);
            }
        }
    }

    private ArrayNode buildArray(TreeMap<Integer, ObjectNode> byIndex, List<ObjectNode> unindexed) {
        ArrayNode array = nodeFactory.arrayNode();
        for (ObjectNode element : byIndex.values()) {
            array.add(element.deepCopy());
        }
        for (ObjectNode element : unindexed) {
            array.add(element.deepCopy());
        }
        return array;
    }

    private void deepMergeInto(ObjectNode target, JsonNode source) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                // last non-null wins — a null-valued partial field never erases an already-merged value
                continue;
            }
            JsonNode existing = target.get(key);
            if (value.isObject() && existing instanceof ObjectNode existingObject) {
                deepMergeInto(existingObject, value);
            } else {
                target.set(key, value.deepCopy());
            }
        }
    }
}
