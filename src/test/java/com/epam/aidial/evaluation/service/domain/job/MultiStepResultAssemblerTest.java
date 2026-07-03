package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MultiStepResultAssembler")
class MultiStepResultAssemblerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long NOW_MS = FIXED_CLOCK.millis();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MultiStepResultAssembler assembler = new MultiStepResultAssembler(objectMapper, FIXED_CLOCK);

    private static TestCaseRunInput input() {
        return TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc-1")
                .testCaseData("{\"turns\":[\"a\"]}")
                .build();
    }

    private static EvaluationContext context() {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .createdAtMs(NOW_MS)
                .build();
    }

    @Test
    @DisplayName("success() maps outcome fields, fixed-clock timestamps, and empty extraction warnings")
    void successMapsAllFields() {
        final TestCaseRunInput input = input();
        final EvaluationContext context = context();
        final ConversationOutcome outcome = new ConversationOutcome(
                ExecutionStatus.SUCCESS, 200, "{\"req\":1}", "{\"resp\":2}", 2, "{\"answer\":[\"Paris\"]}");

        final TestCaseRunResult result = assembler.success(input, context, 3, "trace-1", NOW_MS - 100, outcome);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        assertThat(result.getRequestBody()).isEqualTo("{\"req\":1}");
        assertThat(result.getResponseBody()).isEqualTo("{\"resp\":2}");
        assertThat(result.getRetryCount()).isEqualTo(2);
        assertThat(result.getExtractedColumns()).isEqualTo("{\"answer\":[\"Paris\"]}");
        assertThat(result.getExtractionWarnings()).isEqualTo("[]");
        assertThat(result.getLogDetails()).isNull();
        assertThat(result.getRunIndex()).isEqualTo(3);
        assertThat(result.getTraceId()).isEqualTo("trace-1");
        assertThat(result.getTestCaseId()).isEqualTo(input.getTestCaseId());
        assertThat(result.getTestSuiteRunId()).isEqualTo(context.getRunId());
        assertThat(result.getTestSuiteId()).isEqualTo(context.getSuiteId());
        assertThat(result.getCreatedAtMs()).isEqualTo(NOW_MS);
        assertThat(result.getExecStartedAtMs()).isEqualTo(NOW_MS - 100);
        assertThat(result.getExecCompletedAtMs()).isEqualTo(NOW_MS);
        assertThat(result.getExecDurationMs()).isEqualTo(100);
    }

    @Test
    @DisplayName("dataError() yields an ERROR result with empty columns and an error logDetails envelope")
    void dataErrorBuildsErrorResult() {
        final TestCaseRunResult result =
                assembler.dataError(input(), context(), 0, "trace-1", NOW_MS - 50, "bad data shape");

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.getRequestBody()).isNull();
        assertThat(result.getResponseBody()).isNull();
        assertThat(result.getResponseStatusCode()).isNull();
        assertThat(result.getExtractedColumns()).isEqualTo("{}");
        assertThat(result.getExtractionWarnings()).isEqualTo("[]");
        assertThat(result.getRetryCount()).isEqualTo(0);
        assertThat(result.getLogDetails()).isEqualTo("{\"error\":\"bad data shape\"}");
    }
}
