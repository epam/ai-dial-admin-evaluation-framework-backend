package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.config.properties.TargetProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.EvaluationTraceSession;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.McpSuite;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinition;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinitions;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds an {@link EvaluationContext} from a fetched {@link TestSuiteResponseDto} and CLI configuration,
 * overriding the source suite's recorded deployment reference with the CLI-configured target deployment.
 *
 * <p>The caller credential is an API key sourced from {@link TargetProperties#getApiKey()}, and
 * timestamps come from the injected {@link Clock}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class EvaluationContextFactory {

    private final EvalCliProperties cliProperties;
    private final TargetProperties targetProperties;
    private final Clock clock;
    private final RunExecutorFactory runExecutorFactory;
    private final OpenTelemetry openTelemetry;

    /**
     * Creates a new {@link EvaluationContext} for the given suite and test-case count.
     *
     * <p>For deployment suites the {@code target} of the returned context's suite wraps the CLI-configured
     * <em>target</em> deployment reference, <strong>not</strong> the suite's source-side ref —
     * this is what routes execution to the target environment.
     *
     * <p>{@code testCaseSchema} is set as {@code snapshotTestCaseSchema} on the built context — the
     * one input {@link com.epam.aidial.evaluation.runner.job.PerTurnBindingDetector} needs to decide,
     * per request in the chain, whether a bound field is per-turn. A null or empty schema causes
     * every case to execute single-turn (see {@code cli-multi-turn-multi-request-parity} design.md
     * Decision 7 for the caller-side guard against silently downgrading a genuinely multi-turn run).
     *
     * @param suite          the suite configuration fetched from the source EF
     * @param numberOfTestCases total runnable test-case count (used for progress tracking)
     * @param targetDeploymentRef the deployment reference for the target environment to invoke
     * @param testCaseSchema the bound dataset's test-case schema fetched alongside the suite, or
     *                       null when the fetch bundle predates the schema field
     * @return a fully wired {@link EvaluationContext} ready for use with {@link
     *     com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory}
     */
    public EvaluationContext create(
            TestSuiteResponseDto suite,
            int numberOfTestCases,
            DeploymentReferenceDto targetDeploymentRef,
            List<FieldDefinitionDto> testCaseSchema) {
        final EvalCliProperties.Run run = cliProperties.getRun();
        final SuiteDefinition definition = resolveSuite(suite, targetDeploymentRef);
        final UUID runId = UUID.randomUUID();
        return EvaluationContext.builder()
                .runId(runId)
                .traceSession(new EvaluationTraceSession(
                        openTelemetry, runId, suite.getId(), Span.current().getSpanContext()))
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
                // Worker executor for this run; thread mode follows spring.threads.virtual.enabled
                // (VIRTUAL_THREADS_ENABLED); owned and shut down by RunOrchestrationService
                .executor(runExecutorFactory.newWorkerExecutor())
                // Caller credential for per-worker propagation: the CLI always authenticates to the
                // target DIAL Core with a static API key
                .credential(CallerCredential.apiKey(targetProperties.getApiKey()))
                .createdAtMs(clock.millis())
                .suite(definition)
                .snapshotTestCaseSchema(testCaseSchema)
                .build();
    }

    private SuiteDefinition resolveSuite(TestSuiteResponseDto suite, DeploymentReferenceDto targetDeploymentRef) {
        return switch (SuiteDefinitions.fromSuiteResponse(suite)) {
            // The CLI overrides the recorded deployment ref with the target environment's
            case HttpSuite h
            when h.target() instanceof DeploymentTarget -> h.withTarget(new DeploymentTarget(targetDeploymentRef));
            case HttpSuite h -> h;
            case McpSuite m -> m;
        };
    }
}
