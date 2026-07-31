package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("StreamingResponseAccumulator")
class StreamingResponseAccumulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // Fixed clock at epoch 10 000 ms — used by SseEventParser inside the accumulator
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"));
    // Large timeouts so neither bound trips under the FIXED_CLOCK (now never advances).
    private static final long LARGE_IDLE_TIMEOUT_MS = 600_000L;
    private static final long LARGE_MAX_TOTAL_MS = 3_600_000L;
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
                    response.get("choices").get(0).get("message").get("content").asString();
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
            assertThat(choice.get("finish_reason").asString()).isEqualTo("stop");
            assertThat(choice.get("index").asInt()).isEqualTo(0);

            JsonNode message = choice.get("message");
            assertThat(message.get("role").asString()).isEqualTo("assistant");
            assertThat(message.get("content").asString()).isEqualTo("Test response");
        }

        @Test
        @DisplayName("Should produce byte-identical responseBody when no custom_content is present in any chunk")
        void accumulate_noCustomContent_responseBodyByteIdentical() {
            InputStream stream =
                    buildSseStream("data: {\"choices\":[{\"delta\":{\"content\":\"Test response\"}}]}", "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            assertThat(accumulator.getResponseBody())
                    .isEqualTo("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Test response\"},"
                            + "\"finish_reason\":\"stop\",\"index\":0}]}");
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
                    response.get("choices").get(0).get("message").get("content").asString();
            assertThat(content).isEqualTo("Before");
        }

        @Test
        @DisplayName("Should propagate TIMEOUT status when the underlying parser hits the idle timeout")
        void accumulate_idleTimeoutExceeded_setsTimeoutStatus() {
            // Scripted reads: entry=0, chunk1=100, blank=200 (event #1 dispatched), chunk2=5000.
            // idleTimeout=1000: the 200→5000 gap exceeds it, so parsing stops on the second chunk.
            SseEventParser timedParser = new SseEventParser(OBJECT_MAPPER, new AdvancingClock(0, 100, 200, 5_000));
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    timedParser, OBJECT_MAPPER, 1_000L, LARGE_MAX_TOTAL_MS, LARGE_MAX_RESPONSE_SIZE);

            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}");

            accumulator.accumulate(stream);

            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
        }

        @Test
        @DisplayName("Should propagate TIMEOUT status when the parser hits the max-total cap under continuous activity")
        void accumulate_maxTotalCapExceeded_setsTimeoutStatus() {
            // Gaps of 300ms (idle 10_000 never trips), but total crosses the 1000ms hard cap.
            SseEventParser timedParser = new SseEventParser(OBJECT_MAPPER, new AdvancingClock(0, 300, 600, 900, 1_200));
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    timedParser, OBJECT_MAPPER, 10_000L, 1_000L, LARGE_MAX_RESPONSE_SIZE);

            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}");

            accumulator.accumulate(stream);

            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
        }

        @Test
        @DisplayName("Should truncate and set ERROR status when response size limit is exceeded in OpenAI mode")
        void accumulate_sizeLimitExceeded_truncatesAndSetsError() {
            long smallSizeLimit = 5L;
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    sseEventParser, OBJECT_MAPPER, LARGE_IDLE_TIMEOUT_MS, LARGE_MAX_TOTAL_MS, smallSizeLimit);

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
    // OpenAI mode — DIAL custom_content extension field
    // =====================================================================

    @Nested
    @DisplayName("OpenAI mode — custom_content")
    class CustomContent {

        @Test
        @DisplayName("Should merge a stage delivered as name/content/status partials across chunks by index")
        void accumulate_stagePartialsAcrossChunks_mergedOntoAssembledMessage() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\",\"custom_content\":"
                            + "{\"stages\":[{\"index\":0,\"name\":\"Searching\"}]}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"stages\":[{\"index\":0,\"content\":\"Looking up docs\"}]}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"stages\":[{\"index\":0,\"status\":\"completed\"}]}}}]}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode message = OBJECT_MAPPER
                    .readTree(accumulator.getResponseBody())
                    .get("choices")
                    .get(0)
                    .get("message");
            assertThat(message.get("content").asString()).isEqualTo("Hi");
            JsonNode stage = message.get("custom_content").get("stages").get(0);
            assertThat(stage.get("name").asString()).isEqualTo("Searching");
            assertThat(stage.get("content").asString()).isEqualTo("Looking up docs");
            assertThat(stage.get("status").asString()).isEqualTo("completed");
        }

        @Test
        @DisplayName("Should merge an attachment split across multiple chunks")
        void accumulate_attachmentSplitAcrossChunks_mergedOntoAssembledMessage() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"attachments\":[{\"index\":0,\"type\":\"image/png\"}]}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"attachments\":[{\"index\":0,\"title\":\"chart.png\"}]}}}]}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode attachment = OBJECT_MAPPER
                    .readTree(accumulator.getResponseBody())
                    .get("choices")
                    .get(0)
                    .get("message")
                    .get("custom_content")
                    .get("attachments")
                    .get(0);
            assertThat(attachment.get("type").asString()).isEqualTo("image/png");
            assertThat(attachment.get("title").asString()).isEqualTo("chart.png");
        }

        @Test
        @DisplayName("Should merge two attachments whose partial updates arrive interleaved")
        void accumulate_twoAttachmentsInterleaved_mergedIndependentlyByIndex() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"attachments\":[{\"index\":0,\"title\":\"first.png\"}]}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"attachments\":[{\"index\":1,\"title\":\"second.png\"}]}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"attachments\":[{\"index\":0,\"url\":\"files/first.png\"}]}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                            + "{\"attachments\":[{\"index\":1,\"url\":\"files/second.png\"}]}}}]}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode attachments = OBJECT_MAPPER
                    .readTree(accumulator.getResponseBody())
                    .get("choices")
                    .get(0)
                    .get("message")
                    .get("custom_content")
                    .get("attachments");
            assertThat(attachments).hasSize(2);
            assertThat(attachments.get(0).get("title").asString()).isEqualTo("first.png");
            assertThat(attachments.get(0).get("url").asString()).isEqualTo("files/first.png");
            assertThat(attachments.get(1).get("title").asString()).isEqualTo("second.png");
            assertThat(attachments.get(1).get("url").asString()).isEqualTo("files/second.png");
        }

        @Test
        @DisplayName("Should overwrite a scalar custom_content field with the last non-null value across chunks")
        void accumulate_scalarField_lastNonNullWins() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":{\"state\":\"pending\"}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":{\"state\":\"running\"}}}]}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode message = OBJECT_MAPPER
                    .readTree(accumulator.getResponseBody())
                    .get("choices")
                    .get(0)
                    .get("message");
            assertThat(message.get("custom_content").get("state").asString()).isEqualTo("running");
        }

        @Test
        @DisplayName("Should accumulate custom_content from a chunk that carries no text delta at all")
        void accumulate_customContentOnChunkWithNoTextDelta_stillAccumulated() throws Exception {
            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"custom_content\":{\"state\":\"running\"}}}]}",
                    "data: [DONE]");

            StreamingResponseAccumulator accumulator = createAccumulator();
            accumulator.accumulate(stream);

            JsonNode message = OBJECT_MAPPER
                    .readTree(accumulator.getResponseBody())
                    .get("choices")
                    .get(0)
                    .get("message");
            assertThat(message.get("content").asString()).isEqualTo("Hello");
            assertThat(message.get("custom_content").get("state").asString()).isEqualTo("running");
        }

        @Test
        @DisplayName("Should leave the truncated-stream string path unchanged when custom_content is present")
        void accumulate_truncatedStreamWithCustomContent_bodyStaysPlainString() {
            // First chunk's data payload is 77 bytes (fits under 100 and gets dispatched, carrying
            // custom_content); the second chunk's 43 bytes pushes accumulated bytes to 120 > 100,
            // truncating the stream on the second chunk (matches SseEventParser's accounting, which
            // measures only the "data:" payload, not the "data: " line prefix).
            long sizeLimitThatAdmitsOnlyFirstChunk = 100L;
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    sseEventParser,
                    OBJECT_MAPPER,
                    LARGE_IDLE_TIMEOUT_MS,
                    LARGE_MAX_TOTAL_MS,
                    sizeLimitThatAdmitsOnlyFirstChunk);

            InputStream stream = buildSseStream(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\",\"custom_content\":"
                            + "{\"state\":\"running\"}}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\" more\"}}]}",
                    "data: [DONE]");

            accumulator.accumulate(stream);

            assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(accumulator.getTruncationWarning()).isNotNull();
            // Truncated path stores a plain JSON string of the accumulated content — no object
            // structure, no custom_content attached, even though custom_content had been accumulated
            // from the surviving first chunk.
            JsonNode body = OBJECT_MAPPER.readTree(accumulator.getResponseBody());
            assertThat(body.isString()).isTrue();
            assertThat(body.asString()).isEqualTo("Hi");
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
            assertThat(events.get(0).get("event").asString()).isEqualTo("message");
            assertThat(events.get(0).get("data").get("text").asString()).isEqualTo("chunk1");
            assertThat(events.get(1).get("data").get("text").asString()).isEqualTo("chunk2");
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
            assertThat(events.get(0).get("event").asString()).isEqualTo("process_entities");
            assertThat(events.get(1).get("event").asString()).isEqualTo("success");
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
            assertThat(events.get(0).get("data").asString()).isEqualTo("not-valid-json");
            assertThat(events.get(1).get("data").asString()).isEqualTo("also-not-json");
        }

        @Test
        @DisplayName("Should return ERROR and partial envelope when size limit is exceeded")
        void accumulate_sizeLimitExceededStructuredMode_returnsPartialEnvelope() throws Exception {
            long smallLimit = 20L;
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    sseEventParser, OBJECT_MAPPER, LARGE_IDLE_TIMEOUT_MS, LARGE_MAX_TOTAL_MS, smallLimit);

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
                sseEventParser, OBJECT_MAPPER, LARGE_IDLE_TIMEOUT_MS, LARGE_MAX_TOTAL_MS, LARGE_MAX_RESPONSE_SIZE);
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
