package com.epam.aidial.evaluation.runner.job;

import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accumulates Anthropic Messages API content blocks across incremental {@code content_block_start} and
 * {@code content_block_delta} SSE events, keyed by block index. Not a Spring bean — instantiate a fresh
 * instance per accumulation run alongside {@link StreamingResponseAccumulator}.
 *
 * <p>{@code text} blocks accumulate {@code delta.text} ({@code text_delta} events); {@code tool_use}
 * blocks accumulate {@code delta.partial_json} ({@code input_json_delta} events) and reassemble it into
 * parsed JSON when the final content array is built, falling back to the raw accumulated string if
 * parsing fails. Any other block type is passed through unchanged from its {@code content_block_start}
 * shape — no delta reconstruction.
 */
final class AnthropicContentAccumulator {

    private static final String TYPE_FIELD = "type";
    private static final String TEXT_TYPE = "text";
    private static final String TOOL_USE_TYPE = "tool_use";
    private static final String TEXT_FIELD = "text";
    private static final String INPUT_FIELD = "input";
    private static final String PARTIAL_JSON_FIELD = "partial_json";

    private final ObjectMapper objectMapper;
    private final JsonNodeFactory nodeFactory = JsonNodeFactory.instance;

    private final TreeMap<Integer, ObjectNode> blockStarts = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> textDeltas = new TreeMap<>();
    private final TreeMap<Integer, StringBuilder> jsonDeltas = new TreeMap<>();

    AnthropicContentAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Seeds a content block at {@code index} from its {@code content_block_start.content_block} shape. */
    void onBlockStart(int index, JsonNode contentBlock) {
        if (contentBlock != null && contentBlock.isObject()) {
            blockStarts.put(index, (ObjectNode) contentBlock.deepCopy());
        }
    }

    /** Appends one {@code content_block_delta} event's {@code delta} payload for block {@code index}. */
    void onBlockDelta(int index, JsonNode delta) {
        if (delta == null) {
            return;
        }
        JsonNode text = delta.get(TEXT_FIELD);
        if (text != null && text.isString()) {
            textDeltas.computeIfAbsent(index, i -> new StringBuilder()).append(text.asString());
        }
        JsonNode partialJson = delta.get(PARTIAL_JSON_FIELD);
        if (partialJson != null && partialJson.isString()) {
            jsonDeltas.computeIfAbsent(index, i -> new StringBuilder()).append(partialJson.asString());
        }
    }

    /**
     * Builds the final {@code content} array in ascending block-index order: {@code text} blocks get
     * their accumulated text, {@code tool_use} blocks get their accumulated JSON parsed into {@code
     * input} (raw accumulated string as a fallback on parse failure), any other block type is returned
     * unchanged from its seeded {@code content_block_start} shape.
     */
    ArrayNode buildContentArray() {
        ArrayNode array = nodeFactory.arrayNode();
        for (Map.Entry<Integer, ObjectNode> entry : blockStarts.entrySet()) {
            ObjectNode block = entry.getValue().deepCopy();
            String type = blockType(block);
            if (TEXT_TYPE.equals(type)) {
                StringBuilder accumulated = textDeltas.get(entry.getKey());
                if (accumulated != null) {
                    block.put(TEXT_FIELD, accumulated.toString());
                }
            } else if (TOOL_USE_TYPE.equals(type)) {
                StringBuilder accumulated = jsonDeltas.get(entry.getKey());
                if (accumulated != null) {
                    setToolUseInput(block, accumulated.toString());
                }
            }
            array.add(block);
        }
        return array;
    }

    /** Concatenation of all {@code text}-block accumulated text, in ascending block-index order. */
    String accumulatedText() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<Integer, ObjectNode> entry : blockStarts.entrySet()) {
            if (TEXT_TYPE.equals(blockType(entry.getValue()))) {
                StringBuilder accumulated = textDeltas.get(entry.getKey());
                if (accumulated != null) {
                    result.append(accumulated);
                }
            }
        }
        return result.toString();
    }

    private String blockType(ObjectNode block) {
        JsonNode type = block.get(TYPE_FIELD);
        return type != null && type.isString() ? type.asString() : null;
    }

    private void setToolUseInput(ObjectNode block, String accumulatedJson) {
        try {
            JsonNode parsed =
                    accumulatedJson.isEmpty() ? nodeFactory.objectNode() : objectMapper.readTree(accumulatedJson);
            block.set(INPUT_FIELD, parsed);
        } catch (JacksonException e) {
            block.put(INPUT_FIELD, accumulatedJson);
        }
    }
}
