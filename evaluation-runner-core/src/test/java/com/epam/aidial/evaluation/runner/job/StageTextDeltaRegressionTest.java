package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Stage text deltas")
class StageTextDeltaRegressionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should concatenate repeated stage name and content fragments by index")
    void accumulate_repeatedStageText_preservesAllFragments() {
        final CustomContentAccumulator accumulator = new CustomContentAccumulator();
        for (final JsonNode delta : deltas()) {
            accumulator.accumulate(delta);
        }

        assertThat(accumulator.getMerged()).isEqualTo(expectedCustomContent());
    }

    @Test
    @DisplayName("Should preserve complete stage text in the assembled streaming response")
    void assemble_repeatedStageText_preservesAllFragments() {
        final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        final StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                new SseEventParser(OBJECT_MAPPER, clock), OBJECT_MAPPER, 60_000L, 60_000L, 1_000_000L);
        final List<SseEvent> events = deltas().stream()
                .map(delta -> {
                    final var payload = OBJECT_MAPPER.createObjectNode();
                    payload.putArray("choices").addObject().putObject("delta").set("custom_content", delta);
                    return new SseEvent("message", payload);
                })
                .toList();

        accumulator.assemble(new SseParseResult(events, ExecutionStatus.SUCCESS, null));

        assertThat(accumulator.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(OBJECT_MAPPER
                        .readTree(accumulator.getResponseBody())
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("custom_content"))
                .isEqualTo(expectedCustomContent());
    }

    private List<JsonNode> deltas() {
        return List.of(
                OBJECT_MAPPER.readTree("""
                        {"stages":[{"index":0,"name":"Checking ","content":"Read "}]}
                        """),
                OBJECT_MAPPER.readTree("""
                        {"stages":[{"index":0,"name":""}]}
                        """),
                OBJECT_MAPPER.readTree("""
                        {"stages":[{"index":0,"name":"inventory","content":"the list."}]}
                        """),
                OBJECT_MAPPER.readTree("""
                        {"stages":[{"index":0,"status":"completed"}]}
                        """));
    }

    private JsonNode expectedCustomContent() {
        return OBJECT_MAPPER.readTree("""
                {"stages":[{"index":0,"name":"Checking inventory","content":"Read the list.","status":"completed"}]}
                """);
    }
}
