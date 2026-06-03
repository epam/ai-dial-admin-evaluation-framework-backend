package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StreamingResponseAccumulator")
class StreamingResponseAccumulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // Fixed clock at epoch 10 000 ms — used by SseEventParser inside the accumulator
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"));
    private static final long FAR_FUTURE_DEADLINE_MS = 610_000L; // > FIXED_CLOCK.millis()
    private static final long PAST_DEADLINE_MS = 9_999L; // < FIXED_CLOCK.millis()
    private static final long LARGE_MAX_RESPONSE_SIZE = 10 * 1024 * 1024L;

    private final SseEventParser sseEventParser = new SseEventParser(OBJECT_MAPPER, FIXED_CLOCK);

    // =====================================================================
    // OpenAI mode (unchanged behavior)
    // =====================================================================

    @Nested
    @DisplayName("OpenAI mode")
    class OpenAiMode {

        @Test
        @DisplayName("Should concatenate OpenAI delta content from multiple SSE chunks")
        void accumulate_openAiFormat_concatenatesContent() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            String content =
                    response.get("choices").get(0).get("message").get("content").asText();
            assertThat(content).isEqualTo("Hello world");
            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        }

        @Test
        @DisplayName("Should assemble non-streaming OpenAI response with message structure")
        void accumulate_openAiFormat_assemblesNonStreamingResponse() throws Exception {
            InputStream stream =
                    buildSseStream("data: {\"choices\":[{\"delta\":{\"content\":\"Test response\"}}]}", "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            assertThat(response.has("choices")).isTrue();

            JsonNode choice = response.get("choices").get(0);
            assertThat(choice.get("finish_reason").asText()).isEqualTo("stop");
            assertThat(choice.get("index").asInt()).isEqualTo(0);

            JsonNode message = choice.get("message");
            assertThat(message.get("role").asText()).isEqualTo("assistant");
            assertThat(message.get("content").asText()).isEqualTo("Test response");
        }

        @Test
        @DisplayName("Should stop accumulation when [DONE] event is received")
        void accumulate_doneEvent_stopsAccumulation() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Before\"}}]}",
                    "data: [DONE]",
                    "data: {\"choices\":[{\"delta\":{\"content\":\" After\"}}]}");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            String content =
                    response.get("choices").get(0).get("message").get("content").asText();
            assertThat(content).isEqualTo("Before");
        }

        @Test
        @DisplayName("Should set TIMEOUT status when deadline is exceeded")
        void accumulate_deadlineExceeded_setsTimeoutStatus() {
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    sseEventParser, OBJECT_MAPPER, PAST_DEADLINE_MS, LARGE_MAX_RESPONSE_SIZE);

            InputStream stream =
                    buildSseStream("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}", "data: [DONE]");

            accumulator.accumulate(stream);

            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
        }

        @Test
        @DisplayName("Should truncate and set ERROR status when response size limit is exceeded in OpenAI mode")
        void accumulate_sizeLimitExceeded_truncatesAndSetsError() {
            long smallSizeLimit = 5L;
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    sseEventParser, OBJECT_MAPPER, FAR_FUTURE_DEADLINE_MS, smallSizeLimit);

            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello world this is a long response\"}}]}",
                    "data: [DONE]");

            accumulator.accumulate(stream);

            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(accumulator.getTruncationWarning()).isNotNull();
            assertThat(accumulator.getTruncationWarning()).contains("truncated");
        }

        @Test
        @DisplayName("Named event type forces structured mode even with choices[] data")
        void accumulate_namedEventWithChoices_usesStructuredMode() throws Exception {
            InputStream stream = buildSseStream(
                    "event: custom\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}", "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            // Must be the {"events": [...]} envelope, not an OpenAI response
            assertThat(response.has("events")).isTrue();
            assertThat(response.get("events").isArray()).isTrue();
        }
    }

    // =====================================================================
    // Structured SSE mode
    // =====================================================================

    @Nested
    @DisplayName("Structured SSE mode")
    class StructuredSseMode {

        @Test
        @DisplayName("Should store non-OpenAI events as {\"events\": [...]} envelope")
        void accumulate_nonOpenAiFormat_storesAsEventsEnvelope() throws Exception {
            InputStream stream =
                    buildSseStream("data: {\"text\":\"chunk1\"}", "data: {\"text\":\"chunk2\"}", "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            assertThat(response.has("events")).isTrue();
            JsonNode events = response.get("events");
            assertThat(events.isArray()).isTrue();
            assertThat(events).hasSize(2);
            assertThat(events.get(0).get("event").asText()).isEqualTo("message");
            assertThat(events.get(0).get("data").get("text").asText()).isEqualTo("chunk1");
            assertThat(events.get(1).get("data").get("text").asText()).isEqualTo("chunk2");
            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        }

        @Test
        @DisplayName("Should preserve named event type in envelope")
        void accumulate_namedEvents_preservesEventTypeInEnvelope() throws Exception {
            InputStream stream = buildSseStream(
                    "event: process_entities\ndata: {\"count\":5}",
                    "event: success\ndata: {\"done\":true}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            JsonNode events = response.get("events");
            assertThat(events).hasSize(2);
            assertThat(events.get(0).get("event").asText()).isEqualTo("process_entities");
            assertThat(events.get(1).get("event").asText()).isEqualTo("success");
        }

        @Test
        @DisplayName("Should produce empty envelope for empty SSE stream")
        void accumulate_emptyStream_returnsEmptyEnvelope() throws Exception {
            InputStream stream = buildSseStream("");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            assertThat(response.has("events")).isTrue();
            assertThat(response.get("events").isArray()).isTrue();
            assertThat(response.get("events")).isEmpty();
            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        }

        @Test
        @DisplayName("Should store non-JSON data as string in the envelope")
        void accumulate_nonJsonData_storesAsStringInEnvelope() throws Exception {
            InputStream stream = buildSseStream("data: not-valid-json", "data: also-not-json", "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            JsonNode events = response.get("events");
            assertThat(events.isArray()).isTrue();
            assertThat(events).hasSize(2);
            assertThat(events.get(0).get("data").asText()).isEqualTo("not-valid-json");
            assertThat(events.get(1).get("data").asText()).isEqualTo("also-not-json");
        }

        @Test
        @DisplayName("Should return ERROR and partial envelope when size limit is exceeded")
        void accumulate_sizeLimitExceededStructuredMode_returnsPartialEnvelope() throws Exception {
            long smallLimit = 20L;
            StreamingResponseAccumulator accumulator =
                    new StreamingResponseAccumulator(sseEventParser, OBJECT_MAPPER, FAR_FUTURE_DEADLINE_MS, smallLimit);

            InputStream stream = buildSseStream(
                    "data: {\"a\":1}", // small — fits
                    "data: {\"bigkey\":\"biggervalue\"}", // won't fit
                    "data: [DONE]");

            accumulator.accumulate(stream);

            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
            JsonNode response = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            JsonNode events = response.get("events");
            // Only the first event was included
            assertThat(events).hasSize(1);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private StreamingResponseAccumulator createAccumulator() {
        return new StreamingResponseAccumulator(
                sseEventParser, OBJECT_MAPPER, FAR_FUTURE_DEADLINE_MS, LARGE_MAX_RESPONSE_SIZE);
    }

    /**
     * Builds a proper SSE stream: each element in {@code lines} is treated as a single event
     * (potentially containing internal newlines for multi-field events) separated by blank lines.
     * Each element therefore becomes its own event block terminated by a blank line.
     */
    private InputStream buildSseStream(String... lines) {
        String content = String.join("\n\n", lines) + "\n\n";
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
