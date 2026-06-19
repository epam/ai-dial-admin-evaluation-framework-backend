package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.io.InputStream;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Two-mode SSE response accumulator:
 * <ol>
 *   <li><b>OpenAI mode</b> — auto-detected when the first event has no named {@code event:} type
 *       (type is {@code "message"}) AND its data contains a {@code choices[]} array. Extracts
 *       {@code choices[0].delta.content} from each chunk, concatenates, and assembles a complete
 *       non-streaming chat-completions response.</li>
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
        SseParseResult result =
                sseEventParser.parse(eventStream, idleTimeoutMs, maxTotalDurationMs, maxResponseSizeBytes);

        if (result.status() != ExecutionStatus.SUCCESS) {
            this.executionStatus = result.status();
        }
        this.truncationWarning = result.truncationWarning();

        List<SseEvent> events = result.events();
        boolean isOpenAi = isOpenAiMode(events);

        if (isOpenAi) {
            assembleOpenAiResponse(events, result.status());
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

    private void assembleOpenAiResponse(List<SseEvent> events, ExecutionStatus parseStatus) {
        StringBuilder content = new StringBuilder();
        for (SseEvent event : events) {
            if (event.data() instanceof JsonNode node) {
                String delta = extractOpenAiContent(node);
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                }
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
}
