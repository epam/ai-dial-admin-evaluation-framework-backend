package com.epam.aidial.evaluation.runner.job;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accumulates an OpenAI <b>Responses API</b> SSE stream ({@code event: response.*}) into the very same
 * document a non-streaming Responses call would have returned. Not a Spring bean — instantiate a fresh
 * instance per accumulation run alongside {@link StreamingResponseAccumulator}.
 *
 * <p>Assembly rules, applied as each event's data node is fed in via {@link #accumulate(JsonNode)}:
 *
 * <ul>
 *   <li><b>Terminal event wins.</b> The last event whose {@code type} is {@code response.completed},
 *       {@code response.incomplete} or {@code response.failed} carries the full response object under
 *       {@code response} — that object <em>is</em> the non-streaming body, so it is used verbatim.
 *       Vendor extensions DIAL adds to it survive untouched, and a suite's response columns resolve
 *       identically whether the deployment streamed or not (e.g. {@code output[0].content[0].text},
 *       {@code output_text}, {@code usage.total_tokens}).
 *   <li><b>Deltas are a fallback only.</b> {@code response.output_text.delta} events are concatenated
 *       (across every output/content index, in receipt order) and used solely when the stream ended
 *       without a terminal event — a dropped connection, an idle timeout, or a size cut. See
 *       {@link #getAssembled()} and {@link #getOutputText()}.
 * </ul>
 *
 * <p>{@code response.created} also carries a {@code response} object, but an empty one (no output yet);
 * it is deliberately NOT treated as terminal, so a stream cut right after it falls back to the deltas
 * rather than reporting an empty answer.
 */
final class ResponsesApiAccumulator {

    private static final String TYPE_FIELD = "type";
    private static final String TYPE_PREFIX = "response.";
    private static final String RESPONSE_FIELD = "response";
    private static final String DELTA_FIELD = "delta";
    private static final String OUTPUT_TEXT_DELTA_TYPE = "response.output_text.delta";
    private static final Set<String> TERMINAL_TYPES =
            Set.of("response.completed", "response.incomplete", "response.failed");

    private static final String INCOMPLETE_STATUS = "incomplete";

    private final JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
    private final StringBuilder outputText = new StringBuilder();

    private ObjectNode terminalResponse;

    /**
     * Returns whether this event's data payload is a Responses API event — a JSON object whose
     * {@code type} is a string starting with {@code "response."}. Used for mode detection on the first
     * event of the stream.
     */
    static boolean isResponsesApiEvent(Object data) {
        if (!(data instanceof JsonNode node) || !node.isObject()) {
            return false;
        }
        JsonNode type = node.get(TYPE_FIELD);
        return type != null && type.isString() && type.asString().startsWith(TYPE_PREFIX);
    }

    /**
     * Feeds one event's data payload. No-op when {@code data} is not a JSON object (a Responses stream
     * may still carry unparseable keep-alive payloads) or carries neither a text delta nor a terminal
     * response object.
     */
    void accumulate(Object data) {
        if (!(data instanceof JsonNode node) || !node.isObject()) {
            return;
        }
        JsonNode typeNode = node.get(TYPE_FIELD);
        if (typeNode == null || !typeNode.isString()) {
            return;
        }
        String type = typeNode.asString();
        if (OUTPUT_TEXT_DELTA_TYPE.equals(type)) {
            JsonNode delta = node.get(DELTA_FIELD);
            if (delta != null && delta.isString()) {
                outputText.append(delta.asString());
            }
        } else if (TERMINAL_TYPES.contains(type)) {
            JsonNode response = node.get(RESPONSE_FIELD);
            if (response != null && response.isObject()) {
                terminalResponse = (ObjectNode) response.deepCopy();
            }
        }
    }

    /**
     * Returns the text concatenated from {@code response.output_text.delta} events — the only thing
     * salvageable from a stream that was cut before its terminal event.
     */
    String getOutputText() {
        return outputText.toString();
    }

    /**
     * Returns the assembled non-streaming Responses document: the terminal event's {@code response}
     * object verbatim when the stream completed, otherwise a synthetic object of the same shape carrying
     * the concatenated deltas, so the same response-column expressions still resolve. The synthetic
     * object is marked {@code "status": "incomplete"} — the run's own {@code executionStatus} and
     * truncation warning remain the authoritative signal that the stream was cut.
     */
    ObjectNode getAssembled() {
        if (terminalResponse != null) {
            return terminalResponse;
        }
        ObjectNode content = nodeFactory.objectNode();
        content.put(TYPE_FIELD, "output_text");
        content.put("text", outputText.toString());

        ObjectNode message = nodeFactory.objectNode();
        message.put(TYPE_FIELD, "message");
        message.put("role", "assistant");
        message.put("status", INCOMPLETE_STATUS);
        ArrayNode contentArray = nodeFactory.arrayNode();
        contentArray.add(content);
        message.set("content", contentArray);

        ObjectNode root = nodeFactory.objectNode();
        root.put("status", INCOMPLETE_STATUS);
        ArrayNode output = nodeFactory.arrayNode();
        output.add(message);
        root.set("output", output);
        return root;
    }
}
