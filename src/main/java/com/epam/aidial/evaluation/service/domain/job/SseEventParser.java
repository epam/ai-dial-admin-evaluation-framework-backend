package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Injectable component that parses SSE wire format into structured {@link SseEvent} records.
 *
 * <p>Follows RFC SSE semantics:
 * <ul>
 *   <li>Event type defaults to {@code "message"} when no {@code event:} field is present.</li>
 *   <li>Multiple {@code data:} lines are joined with {@code \n} before JSON parsing.</li>
 *   <li>{@code id:} and {@code retry:} fields are ignored.</li>
 *   <li>Comment lines (starting with {@code :}) are ignored.</li>
 *   <li>{@code data: [DONE]} terminates the stream without emitting an event.</li>
 *   <li>Blank lines trigger event dispatch if at least one {@code data:} line was present.</li>
 *   <li>Event type resets to {@code "message"} after each blank-line-delimited event block.</li>
 * </ul>
 *
 * <p>Enforces deadline (via {@link Clock}) and byte-size limits.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class SseEventParser {

    private static final String DONE_SENTINEL = "[DONE]";
    private static final String DEFAULT_EVENT_TYPE = "message";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Parses the SSE stream into a list of structured events.
     *
     * @param stream      raw SSE input stream
     * @param deadlineMs  epoch-millisecond deadline; parsing stops with {@code TIMEOUT} if exceeded
     * @param maxBytes    accumulated data byte limit; parsing stops with {@code ERROR} if exceeded
     * @return parsed result containing events, status, and optional truncation warning
     */
    public SseParseResult parse(InputStream stream, long deadlineMs, long maxBytes) {
        List<SseEvent> events = new ArrayList<>();
        long accumulatedBytes = 0L;
        String currentEventType = DEFAULT_EVENT_TYPE;
        List<String> dataLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (clock.millis() > deadlineMs) {
                    log.debug("SSE parsing deadline exceeded after {} events", events.size());
                    return new SseParseResult(events, ExecutionStatus.TIMEOUT, null);
                }

                if (line.isEmpty()) {
                    // Blank line: dispatch event if data lines were accumulated
                    if (!dataLines.isEmpty()) {
                        String rawData = String.join("\n", dataLines);
                        long dataBytes = rawData.getBytes(StandardCharsets.UTF_8).length;

                        if (accumulatedBytes + dataBytes > maxBytes) {
                            String warning =
                                    "Response truncated: accumulated " + accumulatedBytes + " bytes, limit " + maxBytes;
                            log.debug("SSE size limit exceeded after {} events", events.size());
                            return new SseParseResult(events, ExecutionStatus.ERROR, warning);
                        }

                        accumulatedBytes += dataBytes;
                        Object data = tryParseJson(rawData);
                        events.add(new SseEvent(currentEventType, data));
                    }
                    // Reset for next event block
                    currentEventType = DEFAULT_EVENT_TYPE;
                    dataLines = new ArrayList<>();

                } else if (line.startsWith("data:")) {
                    String value = stripLeadingSpace(line.substring(5));
                    if (DONE_SENTINEL.equals(value)) {
                        return new SseParseResult(events, ExecutionStatus.SUCCESS, null);
                    }
                    dataLines.add(value);

                } else if (line.startsWith("event:")) {
                    currentEventType = stripLeadingSpace(line.substring(6));

                } else if (line.startsWith(":") || line.startsWith("id:") || line.startsWith("retry:")) {
                    // Comment, id, and retry fields are ignored per SSE spec
                }
                // Unknown field names are ignored per SSE spec
            }
        } catch (IOException e) {
            log.warn("SSE stream read error after {} events: {}", events.size(), e.getMessage(), e);
            return new SseParseResult(events, ExecutionStatus.ERROR, null);
        }

        return new SseParseResult(events, ExecutionStatus.SUCCESS, null);
    }

    private static String stripLeadingSpace(String value) {
        if (value.startsWith(" ")) {
            return value.substring(1);
        }
        return value;
    }

    private Object tryParseJson(String rawData) {
        try {
            JsonNode node = objectMapper.readTree(rawData);
            // readTree may return null or MissingNode for empty/whitespace-only input
            if (node == null || node.isMissingNode()) {
                return rawData;
            }
            return node;
        } catch (IOException e) {
            return rawData;
        }
    }
}
