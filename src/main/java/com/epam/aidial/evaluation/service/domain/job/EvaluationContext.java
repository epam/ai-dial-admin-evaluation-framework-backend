package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.RequestSpec;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ToolReferenceDto;
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

    private final TestSuiteRun testSuiteRun;
    private final int numberOfRuns;
    private final int numberOfTestCases;

    // Execution settings (merged with defaults)
    private final int concurrencyLevel;
    private final long requestTimeoutMs;

    /**
     * Run-wide gate acquired once per outgoing HTTP call — including every multi-turn turn, every
     * multi-request chain request, and every retry. Shared by all workers of the run, which is what makes
     * the limit run-wide. A no-op gate when the run configures no {@code rateLimitRps}.
     *
     * <p>Defaulted rather than left null so a context assembled without one never NPEs at a call site: an
     * absent gate means "no rate limit", which is exactly the semantics of an unset {@code rateLimitRps}.
     */
    @Builder.Default
    private final RunRateLimiter rateLimiter = RunRateLimiter.disabled();

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
     * The run's frozen chain, normalized from the snapshot by {@code ChainNormalizer} — element 0 synthesized
     * from the flat snapshot fields above, elements {@code 1..N-1} from the snapshot's
     * {@code additionalRequests}. Size 1 for a single-request suite, which is the dispatch discriminator:
     * {@code chain.size() > 1} routes to the chain executor. Normalizing here means execution, export
     * planning, and schema discovery all see one consistent representation of "the chain".
     */
    private final List<RequestSpec> chain;

    // Pre-deserialized typed DTOs (deserialized once at run init, immutable for run duration)
    private final McpDeploymentReferenceDto mcpDeploymentRefDto;
    private final ToolReferenceDto toolRefDto;
    private final ArgumentTemplateDto argumentTemplateDto;
    private final List<InputBindingDto> inputBindings;
}
