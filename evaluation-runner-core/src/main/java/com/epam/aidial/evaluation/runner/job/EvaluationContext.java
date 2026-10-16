package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinition;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
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

    // Caller credential for propagation to workers
    private final CallerCredential credential;

    // Created-at timestamp for analytics results (from the run)
    private final long createdAtMs;

    /** What the suite calls and sends: target paired with its invocation shape (HTTP request chain or MCP tool call). */
    private final SuiteDefinition suite;

    /** Resolved suite target (deployment, route or MCP tool). Superseded by {@link #suite}; removed after migration. */
    private final SuiteTarget target;

    // Snapshot-level suite configuration (populated from suite_snapshot or synthesized from live suite).
    // The invocation fields below are superseded by {@link #suite}; removed after migration.
    private final EndpointContractDto snapshotEndpointRef;
    private final RequestTemplateDto snapshotRequestTemplate;
    private final List<InputBindingDto> snapshotInputBindings;
    private final List<ResponseColumnDefinitionDto> snapshotResponseColumns;

    /**
     * The suite's request chain beyond request #0 (index 1..N), in execution order — sourced from
     * {@code SuiteSnapshotDto.additionalRequests}. Superseded by {@link #suite}; removed after migration. The singular {@code snapshot*} fields above remain
     * request #0's definition; the chain executor assembles the ordered chain from them plus this
     * list. Empty for a single-request suite.
     */
    private final List<RequestDefinitionDto> snapshotAdditionalRequests;

    /**
     * User-facing label for request #0, sourced from {@code SuiteSnapshotDto.requestName}. Null when
     * unlabelled. Superseded by {@link #suite}; removed after migration.
     */
    private final String snapshotRequestName;

    /**
     * Dataset test-case schema field definitions at snapshot time, sourced from {@code
     * SuiteSnapshotDto.testCaseSchema}. Used by {@link PerTurnBindingDetector} to resolve whether a bound
     * {@code dataField} is a {@code perTurn = true} field, without a live dataset lookup mid-run.
     */
    private final List<FieldDefinitionDto> snapshotTestCaseSchema;

    // Pre-deserialized typed DTOs (deserialized once at run init, immutable for run duration)
    // argumentTemplateDto and inputBindings are superseded by {@link #suite}; removed after migration.
    private final ArgumentTemplateDto argumentTemplateDto;
    private final List<InputBindingDto> inputBindings;
}
