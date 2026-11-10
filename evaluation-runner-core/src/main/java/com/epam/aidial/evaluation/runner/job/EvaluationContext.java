package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinition;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
public class EvaluationContext {

    private final UUID runId;
    private final UUID suiteId;
    /**
     * Dataset that owns the test cases for this run. Resolved at run start from
     * the snapshot's {@code datasetRef} (or from the live suite for legacy runs that lack
     * a stored snapshot). Used by the legacy-fallback fetch path in {@code InProcessEvaluationExecutor}
     * when {@code test_case_run_inputs} is empty.
     */
    private final UUID datasetId;

    private final int numberOfRuns;
    private final int numberOfTestCases;

    // Execution settings (merged with defaults)
    private final int concurrencyLevel;
    private final long requestTimeoutMs;
    private final Double rateLimitRps;

    // Retry policy (merged with defaults)
    private final int maxRetries;
    private final long retryDelayMs;
    private final double retryBackoffMultiplier;
    private final long maxRetryDelayMs;

    // System settings
    private final int resultBatchSize;
    private final long maxResponseSizeBytes;

    /**
     * Worker executor for this run. Owned, and eventually shut down, by the caller; the runner never
     * creates or shuts it down. Cancellation is delivered by the owner calling shutdownNow().
     */
    private final ExecutorService executor;

    /** Optional for legacy library consumers; production run owners supply and close this session. */
    private final EvaluationTraceSession traceSession;

    // Caller credential for propagation to workers
    private final CallerCredential credential;

    // Created-at timestamp for analytics results (from the run)
    private final long createdAtMs;

    /** What the suite calls and sends: target paired with its invocation shape (HTTP request chain or MCP tool call). */
    private final SuiteDefinition suite;

    /**
     * Dataset test-case schema field definitions at snapshot time, sourced from {@code
     * SuiteSnapshotDto.testCaseSchema}. Used by {@link PerTurnBindingDetector} to resolve whether a bound
     * {@code dataField} is a {@code perTurn = true} field, without a live dataset lookup mid-run.
     */
    private final List<FieldDefinitionDto> snapshotTestCaseSchema;

    /** Where this suite calls; shorthand for {@code getSuite().target()}. */
    public SuiteTarget getTarget() {
        return suite.target();
    }
}
