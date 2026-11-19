package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
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

    /** Nothing was extracted: the result write requires both values (NOT NULL columns). */
    private static final String NO_EXTRACTED_COLUMNS = "{}";

    private static final String NO_EXTRACTION_WARNINGS = "[]";

    private final ObjectMapper objectMapper;

    /**
     * @param suiteId the run's suite id — the result write requires it, so a row without it fails its whole batch
     * @param runCreatedAtMs the run's creation timestamp, carried by every result row of the run; run-scoped
     *     reads select a run's rows by it, so a row stamped with any other value is never read back
     * @param nowMs current time, used only for the execution start/completion timestamps
     */
    public TestCaseRunResult errorResult(
            TestCaseRunInput input, int runIndex, Throwable cause, UUID suiteId, long runCreatedAtMs, long nowMs) {
        String envelope = buildEnvelope(cause);
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(input.getRunId())
                .testSuiteId(suiteId)
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .testCaseData(input.getTestCaseData())
                .executionStatus(ExecutionStatus.ERROR)
                .responseBody(envelope)
                .responseStatusCode(null)
                .execStartedAtMs(nowMs)
                .execCompletedAtMs(nowMs)
                .execDurationMs(0L)
                .extractedColumns(NO_EXTRACTED_COLUMNS)
                .extractionWarnings(NO_EXTRACTION_WARNINGS)
                .retryCount(0)
                .logDetails(null)
                .createdAtMs(runCreatedAtMs)
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
