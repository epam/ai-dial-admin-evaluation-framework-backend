package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Three-mode SSE response accumulator:
 * <ol>
 *   <li><b>OpenAI mode</b> — auto-detected when the first event has no named {@code event:} type
 *       (type is {@code "message"}) AND its data contains a {@code choices[]} array. Extracts
 *       {@code choices[0].delta.content} from each chunk, concatenates, and assembles a complete
 *       non-streaming chat-completions response.</li>
 *   <li><b>Anthropic mode</b> — auto-detected when the first event is named {@code message_start} and
 *       its data contains a {@code message} field. Reassembles Anthropic Messages API content blocks
 *       via {@link AnthropicContentAccumulator} into a complete non-streaming message object.</li>
 *   <li><b>Structured SSE mode</b> — for all other streams. Wraps parsed events in a
 *       {@code {"events": [{event, data}, ...]}} envelope that JSONata expressions can navigate.</li>
 * </ol>
 *
 * <p>Delegates SSE wire format parsing (idle timeout, max-total cap, size limits, event dispatch) to
 * {@link SseEventParser}.
 */
@Slf4j
public class StreamingResponseAccumulator {

    private final SseEventParser sseEventParser;
    private final ObjectMapper objectMapper;
    private final long idleTimeoutMs;
    private final long maxTotalDurationMs;
    private final long maxResponseSizeBytes;

    @Getter
    private ExecutionStatus executionStatus = ExecutionStatus.SUCCESS;

    @Getter
    private String responseBody;

    @Getter
    private String truncationWarning;

    public StreamingResponseAccumulator(
            SseEventParser sseEventParser,
            ObjectMapper objectMapper,
            long idleTimeoutMs,
            long maxTotalDurationMs,
            long maxResponseSizeBytes) {
        this.sseEventParser = sseEventParser;
        this.objectMapper = objectMapper;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxTotalDurationMs = maxTotalDurationMs;
        this.maxResponseSizeBytes = maxResponseSizeBytes;
    }

    /**
     * Accumulates SSE events from the input stream.
     * Returns when stream ends, the idle timeout or max-total cap is exceeded, or size limit is reached.
     */
    public void accumulate(InputStream eventStream) {
        assemble(sseEventParser.parse(eventStream, idleTimeoutMs, maxTotalDurationMs, maxResponseSizeBytes));
    }

    /**
     * Assembly seam: performs mode detection + document assembly over an <strong>already parsed</strong>
     * stream, without touching the stream again. Callers that need the parsed {@link SseEvent} list for
     * their own purposes (the try-out preview exposes it verbatim to the UI) parse once themselves and
     * hand the result here, so their extraction input is the very same document a real run would see.
     */
    public void assemble(SseParseResult result) {
        if (result.status() != ExecutionStatus.SUCCESS) {
            this.executionStatus = result.status();
        }
        this.truncationWarning = result.truncationWarning();

        List<SseEvent> events = result.events();
        if (isOpenAiMode(events)) {
            assembleOpenAiResponse(events, result.status());
        } else if (isAnthropicMode(events)) {
            assembleAnthropicResponse(events, result.status());
        } else {
            assembleStructuredSseResponse(events);
        }
    }

    /**
     * OpenAI mode: first event must have type {@code "message"} (no named event line)
     * and its data must contain a {@code choices[]} array.
     */
    private boolean isOpenAiMode(List<SseEvent> events) {
        if (events.isEmpty()) {
            return false;
        }
        SseEvent first = events.get(0);
        if (!"message".equals(first.event())) {
            return false;
        }
        if (!(first.data() instanceof JsonNode node)) {
            return false;
        }
        return node.has("choices") && node.get("choices").isArray();
    }

    /**
     * Anthropic mode: first event must be named {@code message_start} and its data must contain a
     * {@code message} field. A named {@code message_start} event without that field degrades to
     * Structured SSE mode rather than being (mis)treated as Anthropic-shaped.
     */
    private boolean isAnthropicMode(List<SseEvent> events) {
        if (events.isEmpty()) {
            return false;
        }
        SseEvent first = events.get(0);
        if (!"message_start".equals(first.event())) {
            return false;
        }
        if (!(first.data() instanceof JsonNode node)) {
            return false;
        }
        return node.has("message");
    }

    private void assembleAnthropicResponse(List<SseEvent> events, ExecutionStatus parseStatus) {
        ObjectNode message = null;
        final AnthropicContentAccumulator contentAccumulator = new AnthropicContentAccumulator(objectMapper);

        for (SseEvent event : events) {
            if (!(event.data() instanceof JsonNode node)) {
                continue;
            }
            switch (event.event()) {
                case "message_start" -> {
                    if (node.get("message") instanceof ObjectNode baseMessage) {
                        message = baseMessage.deepCopy();
                    }
                }
                case "content_block_start" -> {
                    JsonNode index = node.get("index");
                    if (index != null && index.isNumber()) {
                        contentAccumulator.onBlockStart(index.asInt(), node.get("content_block"));
                    }
                }
                case "content_block_delta" -> {
                    JsonNode index = node.get("index");
                    if (index != null && index.isNumber()) {
                        contentAccumulator.onBlockDelta(index.asInt(), node.get("delta"));
                    }
                }
                case "message_delta" -> {
                    if (message != null) {
                        mergeMessageDelta(message, node);
                    }
                }
                default -> {
                    // content_block_stop, message_stop, ping — no-ops for reconstruction
                }
            }
        }

        try {
            if (parseStatus != ExecutionStatus.SUCCESS) {
                // Truncated — store accumulated text content as a JSON string
                responseBody = objectMapper.writeValueAsString(contentAccumulator.accumulatedText());
            } else {
                if (message == null) {
                    message = objectMapper.createObjectNode();
                }
                message.set("content", contentAccumulator.buildContentArray());
                responseBody = objectMapper.writeValueAsString(message);
            }
        } catch (JacksonException e) {
            log.error("Failed to assemble Anthropic streaming response: {}", e.getMessage(), e);
            executionStatus = ExecutionStatus.ERROR;
        }
    }

    private void mergeMessageDelta(ObjectNode message, JsonNode node) {
        if (node.get("delta") instanceof ObjectNode delta) {
            for (Map.Entry<String, JsonNode> entry : delta.properties()) {
                message.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        if (node.get("usage") instanceof ObjectNode usage) {
            ObjectNode mergedUsage = message.get("usage") instanceof ObjectNode existingUsage
                    ? existingUsage
                    : objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : usage.properties()) {
                mergedUsage.set(entry.getKey(), entry.getValue().deepCopy());
            }
            message.set("usage", mergedUsage);
        }
    }

    private void assembleOpenAiResponse(List<SseEvent> events, ExecutionStatus parseStatus) {
        StringBuilder content = new StringBuilder();
        final CustomContentAccumulator customContentAccumulator = new CustomContentAccumulator();
        for (SseEvent event : events) {
            if (event.data() instanceof JsonNode node) {
                String delta = extractOpenAiContent(node);
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                }
                customContentAccumulator.accumulate(extractOpenAiCustomContent(node));
            }
        }

        try {
            if (parseStatus != ExecutionStatus.SUCCESS) {
                // Truncated — store accumulated content as a JSON string
                responseBody = objectMapper.writeValueAsString(content.toString());
            } else {
                // Assemble as non-streaming OpenAI chat-completions response
                ObjectNode message = objectMapper.createObjectNode();
                message.put("role", "assistant");
                message.put("content", content.toString());

                final ObjectNode mergedCustomContent = customContentAccumulator.getMerged();
                if (mergedCustomContent != null) {
                    message.set("custom_content", mergedCustomContent);
                }

                ObjectNode choice = objectMapper.createObjectNode();
                choice.set("message", message);
                choice.put("finish_reason", "stop");
                choice.put("index", 0);

                ObjectNode root = objectMapper.createObjectNode();
                root.set("choices", objectMapper.createArrayNode().add(choice));
                responseBody = objectMapper.writeValueAsString(root);
            }
        } catch (JacksonException e) {
            log.error("Failed to assemble OpenAI streaming response: {}", e.getMessage(), e);
            executionStatus = ExecutionStatus.ERROR;
        }
    }

    private void assembleStructuredSseResponse(List<SseEvent> events) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            ArrayNode eventsArray = objectMapper.createArrayNode();
            for (SseEvent event : events) {
                ObjectNode eventNode = objectMapper.createObjectNode();
                eventNode.put("event", event.event());
                if (event.data() instanceof JsonNode jsonNode) {
                    eventNode.set("data", jsonNode);
                } else {
                    eventNode.put("data", (String) event.data());
                }
                eventsArray.add(eventNode);
            }
            envelope.set("events", eventsArray);
            responseBody = objectMapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            log.error("Failed to assemble structured SSE response: {}", e.getMessage(), e);
            executionStatus = ExecutionStatus.ERROR;
        }
    }

    private String extractOpenAiContent(JsonNode node) {
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode first = choices.get(0);
        if (first == null) {
            return null;
        }
        JsonNode delta = first.get("delta");
        if (delta == null) {
            return null;
        }
        JsonNode content = delta.get("content");
        if (content == null || content.isNull()) {
            return null;
        }
        return content.asString();
    }

    /**
     * Reads DIAL's {@code custom_content} extension field off {@code choices[0].delta} (NOT
     * {@code choices[0].message}, which is only present in non-streaming responses). Returns
     * {@code null} when absent — most chunks carry no {@code custom_content} at all.
     */
    private JsonNode extractOpenAiCustomContent(JsonNode node) {
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode first = choices.get(0);
        if (first == null) {
            return null;
        }
        JsonNode delta = first.get("delta");
        if (delta == null) {
            return null;
        }
        return delta.get("custom_content");
    }
}
