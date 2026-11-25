package com.epam.aidial.evaluation.runner.job;

import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.BODY;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.HTML_TAG;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.ID;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.INDEX;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.QUOTE;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.SELECTOR;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.TARGET;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.TITLE;
import static com.epam.aidial.evaluation.runner.constants.StreamingExtensionFields.TYPE;

import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Merges only recognized annotation identities, retaining the original array order and shape. */
final class AnnotationDeltaAccumulator {

    private record Identity(String kind, String value) {}

    private AnnotationDeltaAccumulator() {}

    static void merge(ArrayNode target, JsonNode incoming) {
        final Map<Identity, ObjectNode> indexed = new HashMap<>();
        for (final JsonNode element : target) {
            if (element instanceof ObjectNode object) {
                final Identity identity = identity(object);
                if (identity != null) {
                    indexed.putIfAbsent(identity, object);
                }
            }
        }
        for (final JsonNode element : incoming) {
            final Identity identity = element instanceof ObjectNode object ? identity(object) : null;
            final ObjectNode existing = identity == null ? null : indexed.get(identity);
            if (existing == null) {
                final JsonNode copy = element.deepCopy();
                if (identity != null && copy instanceof ObjectNode object) {
                    removeEmptyBodyText(object.get(BODY));
                }
                target.add(copy);
                if (identity != null) {
                    indexed.put(identity, (ObjectNode) copy);
                }
            } else {
                mergeObject(existing, element, true, false);
            }
        }
    }

    private static Identity identity(ObjectNode object) {
        final JsonNode index = object.get(INDEX);
        if (index != null && index.isIntegralNumber() && !index.asString().startsWith("-")) {
            return new Identity(INDEX, index.asString());
        }
        final JsonNode target = object.get(TARGET);
        final JsonNode selector = target != null && target.isObject() ? target.get(SELECTOR) : null;
        if (selector != null && selector.isObject()) {
            final JsonNode type = selector.get(TYPE);
            final JsonNode id = selector.get(ID);
            if (type != null
                    && type.isString()
                    && HTML_TAG.equals(type.asString())
                    && id != null
                    && id.isString()
                    && !id.asString().isEmpty()) {
                return new Identity(SELECTOR, id.asString());
            }
        }
        return null;
    }

    private static void removeEmptyBodyText(JsonNode body) {
        if (body instanceof ObjectNode object) {
            for (final String key : new String[] {TITLE, QUOTE}) {
                final JsonNode value = object.get(key);
                if (value != null && value.isString() && value.asString().isEmpty()) {
                    object.remove(key);
                }
            }
        }
    }

    private static void mergeObject(ObjectNode target, JsonNode source, boolean root, boolean body) {
        for (final Map.Entry<String, JsonNode> entry : source.properties()) {
            final String key = entry.getKey();
            final JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            final JsonNode existing = target.get(key);
            if (body && (TITLE.equals(key) || QUOTE.equals(key)) && value.isString()) {
                if (value.asString().isEmpty()) {
                    continue;
                }
                if (existing != null && existing.isString()) {
                    target.put(key, existing.asString() + value.asString());
                    continue;
                }
            }
            if (value.isObject() && existing instanceof ObjectNode existingObject) {
                mergeObject(existingObject, value, false, root && BODY.equals(key));
            } else if (value.isArray() && existing instanceof ArrayNode existingArray) {
                for (final JsonNode element : value) {
                    existingArray.add(element.deepCopy());
                }
            } else {
                final JsonNode copy = value.deepCopy();
                if (root && BODY.equals(key)) {
                    removeEmptyBodyText(copy);
                }
                target.set(key, copy);
            }
        }
    }
}
