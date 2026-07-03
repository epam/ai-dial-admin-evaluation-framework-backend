package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the persisted {@link TestCaseRunResult} for a multi-step conversation. Two shapes: {@link #success}
 * turns the loop's {@link ConversationOutcome} into a full result (last-turn bodies, column-major
 * extractions, retry count); {@link #dataError} produces an {@code ERROR} result for a per-test-case data
 * problem (no request/response, empty extractions, an {@code {"error": message}} log-details envelope).
 * Both stamp {@code execCompletedAtMs}/{@code execDurationMs} from the injected {@link Clock}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class MultiStepResultAssembler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TestCaseRunResult success(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            ConversationOutcome outcome) {
        final long execCompletedAtMs = clock.millis();
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .testCaseData(input.getTestCaseData())
                .requestBody(outcome.lastRequestBodyJson())
                .responseBody(outcome.lastResponseBodyJson())
                .responseStatusCode(outcome.lastStatusCode())
                .executionStatus(outcome.status())
                .execStartedAtMs(execStartedAtMs)
                .execCompletedAtMs(execCompletedAtMs)
                .execDurationMs(execCompletedAtMs - execStartedAtMs)
                .traceId(traceId)
                .extractedColumns(outcome.extractedColumnsJson())
                .extractionWarnings("[]")
                .retryCount(outcome.lastRetryCount())
                .logDetails(null)
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    public TestCaseRunResult dataError(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            String message) {
        final long execCompletedAtMs = clock.millis();
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .testCaseData(input.getTestCaseData())
                .requestBody(null)
                .responseBody(null)
                .responseStatusCode(null)
                .executionStatus(ExecutionStatus.ERROR)
                .execStartedAtMs(execStartedAtMs)
                .execCompletedAtMs(execCompletedAtMs)
                .execDurationMs(execCompletedAtMs - execStartedAtMs)
                .traceId(traceId)
                .extractedColumns("{}")
                .extractionWarnings("[]")
                .retryCount(0)
                .logDetails(buildErrorLogDetails(message))
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    private String buildErrorLogDetails(String message) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            return node.toString();
        }
    }
}
