package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.config.properties.TargetProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds an {@link EvaluationContext} from a fetched {@link TestSuiteResponseDto} and CLI configuration,
 * overriding the source suite's recorded deployment reference with the CLI-configured target deployment.
 *
 * <p>Token is sourced from {@link TargetProperties#getApiKey()} and timestamps from the injected
 * {@link Clock}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class EvaluationContextFactory {

    private final EvalCliProperties cliProperties;
    private final TargetProperties targetProperties;
    private final Clock clock;

    /**
     * Creates a new {@link EvaluationContext} for the given suite and test-case count.
     *
     * <p>The {@code snapshotDeploymentRef} in the returned context is the CLI-configured
     * <em>target</em> deployment reference, <strong>not</strong> the suite's source-side ref —
     * this is what routes execution to the target environment.
     *
     * @param suite          the suite configuration fetched from the source EF
     * @param numberOfTestCases total runnable test-case count (used for progress tracking)
     * @param targetDeploymentRef the deployment reference for the target environment to invoke
     * @return a fully wired {@link EvaluationContext} ready for use with {@link
     *     com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory}
     */
    public EvaluationContext create(
            TestSuiteResponseDto suite, int numberOfTestCases, DeploymentReferenceDto targetDeploymentRef) {
        final EvalCliProperties.Run run = cliProperties.getRun();
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(suite.getId())
                .datasetId(suite.getDatasetId())
                .numberOfRuns(1)
                .numberOfTestCases(numberOfTestCases)
                // Execution settings from CLI config
                .concurrencyLevel(run.getConcurrencyLevel())
                .requestTimeoutMs(run.getRequestTimeoutMs())
                .rateLimitRps(run.getRateLimitRps())
                // Retry policy from CLI config
                .maxRetries(run.getMaxRetries())
                .retryDelayMs(run.getRetryDelayMs())
                .retryBackoffMultiplier(run.getRetryBackoffMultiplier())
                .maxRetryDelayMs(run.getMaxRetryDelayMs())
                // System settings from CLI config
                .resultBatchSize(run.getResultBatchSize())
                .maxResponseSizeBytes(run.getMaxResponseSizeBytes())
                .cancellationGracePeriodMs(run.getCancellationGracePeriodMs())
                // Cancellation signal — never flipped in this change; wired for TestCaseRunner compatibility
                .cancellationSignal(new AtomicBoolean(false))
                // Auth token for per-worker propagation
                .token(targetProperties.getApiKey())
                .createdAtMs(clock.millis())
                // Suite type from suite config
                .suiteType(
                        suite.getSuiteType() != null
                                ? com.epam.aidial.evaluation.runner.model.SuiteType.valueOf(
                                        suite.getSuiteType().name())
                                : null)
                // Target deployment ref override (replaces source-side ref)
                .snapshotDeploymentRef(targetDeploymentRef)
                .snapshotEndpointRef(suite.getEndpointRef())
                .snapshotRequestTemplate(suite.getRequestTemplate())
                .snapshotInputBindings(suite.getInputBindings() != null ? suite.getInputBindings() : List.of())
                .snapshotResponseColumns(suite.getResponseColumns() != null ? suite.getResponseColumns() : List.of())
                // MCP fields (pass through from source suite if present)
                .mcpDeploymentRefDto(suite.getMcpDeploymentRef() != null ? mapMcpDeploymentRef(suite) : null)
                .toolRefDto(suite.getToolRef())
                .argumentTemplateDto(suite.getArgumentTemplate())
                .inputBindings(suite.getInputBindings() != null ? suite.getInputBindings() : List.of())
                .build();
    }

    private com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto mapMcpDeploymentRef(
            TestSuiteResponseDto suite) {
        // The McpDeploymentReferenceDto is already the runner type (reused in our local DTO)
        return suite.getMcpDeploymentRef();
    }
}
