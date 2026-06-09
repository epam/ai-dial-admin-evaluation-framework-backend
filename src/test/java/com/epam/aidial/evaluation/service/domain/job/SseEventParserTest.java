package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SseEventParser")
class SseEventParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // Fixed clock at epoch 10 000 ms
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"));
    private static final long FAR_FUTURE_DEADLINE = 610_000L;
    private static final long LARGE_MAX_BYTES = 10 * 1024 * 1024L;

    private final SseEventParser parser = new SseEventParser(OBJECT_MAPPER, FIXED_CLOCK);

    // ---- Named events with JSON data ----------------------------------------

    @Test
    @DisplayName("Should parse named SSE event with JSON data")
    void parse_namedEventWithJsonData_parsesCorrectly() {
        InputStream stream = sseStream("event: process_rules\ndata: {\"status\":\"FAILED\"}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        SseEvent event = result.events().get(0);
        assertThat(event.event()).isEqualTo("process_rules");
        assertThat(event.data()).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) event.data()).get("status").asText()).isEqualTo("FAILED");
    }

    // ---- Unnamed events (default type) --------------------------------------

    @Test
    @DisplayName("Should default event type to 'message' when no event: line is present")
    void parse_unnamedEvent_defaultsToMessage() {
        InputStream stream = sseStream("data: {\"result\":\"hello\"}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).event()).isEqualTo("message");
    }

    // ---- Non-JSON data ------------------------------------------------------

    @Test
    @DisplayName("Should store non-JSON data as raw string")
    void parse_nonJsonData_storesAsRawString() {
        InputStream stream = sseStream("data: plain text content\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        SseEvent event = result.events().get(0);
        assertThat(event.data()).isInstanceOf(String.class);
        assertThat(event.data()).isEqualTo("plain text content");
    }

    // ---- Multi-line data ----------------------------------------------------

    @Test
    @DisplayName("Should join multi-line data: fields with newline before JSON parsing")
    void parse_multiLineData_joinsLines() {
        InputStream stream = sseStream("data: line1\ndata: line2\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).data()).isEqualTo("line1\nline2");
    }

    // ---- [DONE] termination -------------------------------------------------

    @Test
    @DisplayName("Should stop on [DONE] and not include it as an event")
    void parse_doneTerminator_stopsWithoutDoneEvent() {
        InputStream stream = sseStream("data: {\"first\":1}\n\ndata: [DONE]\n\ndata: {\"after\":2}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        assertThat(((JsonNode) result.events().get(0).data()).get("first").asInt())
                .isEqualTo(1);
    }

    // ---- Deadline enforcement -----------------------------------------------

    @Test
    @DisplayName("Should return TIMEOUT when deadline is already exceeded")
    void parse_deadlineExceeded_returnsTimeout() {
        // deadlineMs = 9999 < FIXED_CLOCK.millis() = 10000 → immediate timeout
        InputStream stream = sseStream("data: {\"x\":1}\n\ndata: {\"y\":2}\n\n");

        SseParseResult result = parser.parse(stream, 9_999L, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(result.truncationWarning()).isNull();
    }

    // ---- Size limit enforcement ---------------------------------------------

    @Test
    @DisplayName("Should return ERROR with truncation warning when size limit is exceeded")
    void parse_sizeLimitExceeded_returnsError() {
        // First event fits; second event would exceed 20 bytes
        InputStream stream = sseStream("data: {\"a\":1}\n\ndata: {\"bigpayload\":\"12345678901234567890\"}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, 20L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.truncationWarning()).isNotNull();
        assertThat(result.truncationWarning()).contains("truncated");
        // First event was within limit
        assertThat(result.events()).hasSize(1);
    }

    // ---- Comment lines -------------------------------------------------------

    @Test
    @DisplayName("Should ignore SSE comment lines starting with ':'")
    void parse_commentLines_ignored() {
        InputStream stream = sseStream(": this is a comment\ndata: {\"x\":1}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
    }

    // ---- id and retry fields ------------------------------------------------

    @Test
    @DisplayName("Should ignore id: and retry: fields")
    void parse_idAndRetryFields_ignored() {
        InputStream stream = sseStream("id: 42\nretry: 3000\ndata: {\"x\":1}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
    }

    // ---- Event type reset per event block -----------------------------------

    @Test
    @DisplayName("Should reset event type to 'message' after each blank-line-delimited block")
    void parse_eventTypeResets_afterBlankLine() {
        InputStream stream = sseStream("event: typeA\ndata: {}\n\ndata: {}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(2);
        assertThat(result.events().get(0).event()).isEqualTo("typeA");
        assertThat(result.events().get(1).event()).isEqualTo("message");
    }

    // ---- Stream read error --------------------------------------------------

    @Test
    @DisplayName("Should return ERROR with accumulated events on stream read error")
    void parse_streamReadError_returnsErrorWithPartialEvents() {
        // Build an InputStream that delivers one complete event then throws IOException
        String firstEvent = "data: {\"ok\":true}\n\n";
        byte[] firstEventBytes = firstEvent.getBytes(StandardCharsets.UTF_8);
        InputStream errorStream = new InputStream() {
            private int pos = 0;

            @Override
            public int read() throws IOException {
                if (pos < firstEventBytes.length) {
                    return firstEventBytes[pos++] & 0xFF;
                }
                throw new IOException("Simulated stream error");
            }
        };

        SseParseResult result = parser.parse(errorStream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.events()).hasSize(1);
        assertThat(result.truncationWarning()).isNull();
    }

    // ---- Empty stream -------------------------------------------------------

    @Test
    @DisplayName("Should return SUCCESS with empty events list for empty stream")
    void parse_emptyStream_returnsSuccessWithEmptyList() {
        InputStream stream = sseStream("");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).isEmpty();
        assertThat(result.truncationWarning()).isNull();
    }

    // ---- Event type without data line ---------------------------------------

    @Test
    @DisplayName("Should NOT emit event when event: type is set but no data: line follows")
    void parse_eventTypeWithoutData_noEventEmitted() {
        InputStream stream = sseStream("event: typeA\n\ndata: {\"next\":1}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        // typeA block had no data — only the unnamed second event is emitted
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).event()).isEqualTo("message");
    }

    // ---- Empty data payload -------------------------------------------------

    @Test
    @DisplayName("Should emit event with empty string data when data: has no value")
    void parse_emptyDataPayload_emitsEmptyStringEvent() {
        InputStream stream = sseStream("data:\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).data()).isEqualTo("");
    }

    // ---- Whitespace-only data payload ---------------------------------------

    @Test
    @DisplayName("Should preserve whitespace in data payload without trimming")
    void parse_whitespaceOnlyData_preservesWhitespace() {
        // "data:   " → strip one leading space → "  " (two spaces preserved)
        InputStream stream = sseStream("data:   \n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        // Two spaces preserved (not trimmed to empty or single space)
        assertThat(result.events().get(0).data()).isEqualTo("  ");
    }

    // ---- Single event exceeds maxBytes --------------------------------------

    @Test
    @DisplayName("Should not include an event whose payload alone exceeds maxBytes")
    void parse_singleEventExceedsMaxBytes_returnsEmptyList() {
        // A large payload that exceeds 5 bytes
        InputStream stream = sseStream("data: {\"longkey\":\"longvalue\"}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, 5L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.truncationWarning()).isNotNull();
        assertThat(result.events()).isEmpty();
    }

    // ---- Comments interspersed with event fields ----------------------------

    @Test
    @DisplayName("Should preserve event type across interspersed comment lines")
    void parse_commentsBetweenEventFields_preservesEventType() {
        InputStream stream = sseStream("event: typeA\n: this is a comment\ndata: {\"x\":1}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).event()).isEqualTo("typeA");
        assertThat(((JsonNode) result.events().get(0).data()).get("x").asInt()).isEqualTo(1);
    }

    // ---- Consecutive blank lines --------------------------------------------

    @Test
    @DisplayName("Should treat extra blank lines as no-ops and not emit empty events")
    void parse_consecutiveBlankLines_noExtraEventsEmitted() {
        InputStream stream = sseStream("data: {\"a\":1}\n\n\ndata: {\"b\":2}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        // Two events, not three
        assertThat(result.events()).hasSize(2);
    }

    // ---- CRLF line endings --------------------------------------------------

    @Test
    @DisplayName("Should handle CRLF line endings identically to LF")
    void parse_crlfLineEndings_parsedCorrectly() {
        InputStream stream = sseStream("event: typeX\r\ndata: {\"crlf\":true}\r\n\r\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).event()).isEqualTo("typeX");
        assertThat(((JsonNode) result.events().get(0).data()).get("crlf").asBoolean())
                .isTrue();
    }

    // ---- Byte counting with multi-line data ---------------------------------

    @Test
    @DisplayName("Should count joined multi-line data bytes toward the size limit")
    void parse_multiLineDataBytesCounted() {
        // "line1\nline2" = 11 bytes; limit is 15 so it fits, third event would overflow
        InputStream stream = sseStream("data: line1\ndata: line2\n\ndata: more_data\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, 14L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.ERROR);
        // First event (11 bytes) fits; second event (9 bytes) would push total to 20 > 14
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).data()).isEqualTo("line1\nline2");
    }

    // ---- Multiple events with different types --------------------------------

    @Test
    @DisplayName("Should parse multiple named events and return them in order")
    void parse_multipleNamedEvents_returnedInOrder() {
        InputStream stream = sseStream("event: start\ndata: {\"phase\":\"init\"}\n\n"
                + "event: process\ndata: {\"phase\":\"run\"}\n\n"
                + "event: done\ndata: {\"phase\":\"end\"}\n\n");

        SseParseResult result = parser.parse(stream, FAR_FUTURE_DEADLINE, LARGE_MAX_BYTES);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.events()).hasSize(3);
        assertThat(result.events().get(0).event()).isEqualTo("start");
        assertThat(result.events().get(1).event()).isEqualTo("process");
        assertThat(result.events().get(2).event()).isEqualTo("done");
    }

    // ---- Helpers ------------------------------------------------------------

    private static InputStream sseStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
