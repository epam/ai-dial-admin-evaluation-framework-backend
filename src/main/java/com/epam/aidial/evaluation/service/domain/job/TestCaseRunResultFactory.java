package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds synthetic ERROR rows for test cases whose worker threw an exception.
 *
 * <p>The factory MUST NOT throw — even adversarial inputs (e.g., a {@link Throwable}
 * whose {@link Throwable#getMessage()} itself throws) result in a row with a
 * safe-fallback envelope, never a propagated exception. This is intentional: the
 * factory is invoked from a worker's broad catch block, where the only acceptable
 * failure mode is "log loudly and move on" — nothing should be allowed to abort
 * the executor lifecycle from inside synthesis.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseRunResultFactory {

    private static final String FALLBACK_ENVELOPE =
            "{\"error\":{\"type\":\"Unknown\",\"message\":\"\",\"origin\":\"executor\"}}";

    private final ObjectMapper objectMapper;

    /**
     * @param requestLabel the run's request-0 label, so this row carries the same non-null
     *                     {@code request_label} as every row an executor writes; a synthetic row is still a
     *                     result row in the grid and the CSV export
     */
    public TestCaseRunResult errorResult(
            TestCaseRunInput input, int runIndex, Throwable cause, long nowMs, String requestLabel) {
        String envelope = buildEnvelope(cause);
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(input.getRunId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .requestLabel(requestLabel)
                .testCaseData(input.getTestCaseData())
                .executionStatus(ExecutionStatus.ERROR)
                .responseBody(envelope)
                .responseStatusCode(null)
                .execStartedAtMs(nowMs)
                .execCompletedAtMs(nowMs)
                .execDurationMs(0L)
                .retryCount(0)
                .logDetails(null)
                .createdAtMs(nowMs)
                .build();
    }

    private String buildEnvelope(Throwable cause) {
        try {
            String type = cause == null ? "Unknown" : cause.getClass().getSimpleName();
            String message = safeMessage(cause);
            ObjectNode error = objectMapper.createObjectNode();
            error.put("type", type);
            error.put("message", message);
            error.put("origin", "executor");
            ObjectNode root = objectMapper.createObjectNode();
            root.set("error", error);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to serialize synthetic ERROR envelope, falling back: {}", e.getMessage(), e);
            return FALLBACK_ENVELOPE;
        }
    }

    private String safeMessage(Throwable cause) {
        if (cause == null) {
            return "";
        }
        try {
            String msg = cause.getMessage();
            return msg == null ? "" : msg;
        } catch (Exception e) {
            log.warn("Adversarial Throwable.getMessage() threw: {}", e.getMessage(), e);
            return "";
        }
    }
}
