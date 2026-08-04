package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final long cancellationGracePeriodMs;

    // Cancellation signal
    private final AtomicBoolean cancellationSignal;

    // Token for propagation to workers
    private final String token;

    // Created-at timestamp for analytics results (from the run)
    private final long createdAtMs;

    // Suite type (DEPLOYMENT or MCP_TOOL)
    private final SuiteType suiteType;

    // Snapshot-level suite configuration (populated from suite_snapshot or synthesized from live suite)
    private final DeploymentReferenceDto snapshotDeploymentRef;
    private final EndpointContractDto snapshotEndpointRef;
    private final RequestTemplateDto snapshotRequestTemplate;
    private final List<InputBindingDto> snapshotInputBindings;
    private final List<ResponseColumnDefinitionDto> snapshotResponseColumns;

    /**
     * Dataset test-case schema field definitions at snapshot time, sourced from {@code
     * SuiteSnapshotDto.testCaseSchema}. Used by {@link PerTurnBindingDetector} to resolve whether a bound
     * {@code dataField} is a {@code perTurn = true} field, without a live dataset lookup mid-run.
     */
    private final List<FieldDefinitionDto> snapshotTestCaseSchema;

    // Pre-deserialized typed DTOs (deserialized once at run init, immutable for run duration)
    private final McpDeploymentReferenceDto mcpDeploymentRefDto;
    private final ToolReferenceDto toolRefDto;
    private final ArgumentTemplateDto argumentTemplateDto;
    private final List<InputBindingDto> inputBindings;
}
