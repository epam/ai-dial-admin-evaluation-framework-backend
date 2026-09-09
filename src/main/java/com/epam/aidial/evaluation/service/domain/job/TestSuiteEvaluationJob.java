package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.query.service.QueryDslRunnableTestCaseSelector;
import com.epam.aidial.evaluation.query.service.metricscore.MetricScoreComputationExecutor;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.ExecutionSettingsDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RetryPolicyDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.RunErrorDetailsDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.SuiteSnapshotBuilder;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorCategory;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotDatasetMissingException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteEvaluationJob {

    private static final int SNAPSHOT_MAX_RETRIES = 2;
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";
    private static final int SNAPSHOT_PAGE_SIZE = 100;

    private final TestSuiteRunRepository repository;
    private final TestSuiteRepository testSuiteRepository;
    private final DatasetRepository datasetRepository;
    private final QueryDslRunnableTestCaseSelector runnableTestCaseSelector;
    private final TestCaseRunInputRepository testCaseRunInputRepository;
    private final TestSuiteRunSseService sseService;
    private final EvaluationRunProperties evaluationRunProperties;
    private final ObjectMapper objectMapper;
    private final SuiteSnapshotBuilder suiteSnapshotBuilder;
    private final EvaluationExecutor evaluationExecutor;
    private final TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;
    private final MetricEvaluationProperties metricEvaluationProperties;
    private final MetricEvaluationExecutor metricEvaluationExecutor;
    private final MetricScoreComputationExecutor metricScoreComputation;
    private final Clock clock;

    @Qualifier("metaTransactionManager")
    private final PlatformTransactionManager metaTransactionManager;

    @Qualifier("testSuiteRunExecutor")
    private final AsyncTaskExecutor taskExecutor;

    private final ActiveRunRegistry registry;

    /**
     * Registers a {@link RunHandle} for the run (in the caller's thread, so a cancel request arriving
     * before the job thread starts still finds it) and submits {@link #run} to the shared run executor.
     * If submission itself fails (executor rejection or any other exception), the handle is removed and
     * closed before the exception is rethrown, so callers keep their existing rejection-compensation logic.
     */
    public void dispatch(UUID runId, String token, boolean skipDeploymentPhase) {
        RunHandle handle = registry.register(runId);
        try {
            taskExecutor.execute(() -> run(runId, token, skipDeploymentPhase, handle));
        } catch (RuntimeException | Error e) {
            // Platform-mode thread exhaustion at task submission surfaces as OutOfMemoryError, not a
            // RuntimeException; without this branch it would leak the just-registered handle and strand
            // the run PENDING with no cleanup. The Error is left for the caller to propagate.
            registry.remove(runId);
            handle.close();
            throw e;
        }
    }

    /**
     * Runs the whole evaluation job for a run, on the run's shared worker executor thread. Never
     * interrupted (see {@code design.md} decision D3): cancellation is observed only via
     * {@link RunHandle#throwIfCancelled()} at phase boundaries and via {@link RejectedExecutionException}
     * when a phase's dispatch onto the (possibly already shut-down) run executor is rejected.
     *
     * <p>Terminal status precedence (design D4): an {@link AnalyticsWriteException} from Phase 2's flush
     * always maps to FAILED, even when the handle was cancelled; a {@link CancellationException} or
     * {@link RejectedExecutionException} maps to CANCELLED; any other exception maps to CANCELLED only if
     * the handle was cancelled, otherwise FAILED.
     */
    void run(UUID runId, String token, boolean skipDeploymentPhase, RunHandle handle) {
        log.info("Starting test suite run {}", runId);
        // True once a status write performed by this job actually affected a row — the terminal SSE
        // notification in finally fires only then, so a no-op status write (run already terminal via
        // a concurrent cancel) does not emit a duplicate status-update event.
        boolean statusChanged = false;
        try {
            handle.throwIfCancelled();

            SnapshotPhaseOutcome snapshotOutcome = executeSnapshotPhase(runId, !skipDeploymentPhase);
            if (snapshotOutcome != SnapshotPhaseOutcome.SUCCESS) {
                statusChanged = snapshotOutcome == SnapshotPhaseOutcome.FAILED_ROW_UPDATED;
                return;
            }

            long now = clock.millis();
            if (repository.updateToRunning(runId, now, now) == 0) {
                log.info("Run {} was cancelled while PENDING; exiting without executing any phase", runId);
                return;
            }
            statusChanged = true;
            notifySse(runId);

            TestSuiteRun run =
                    repository.findById(runId).orElseThrow(() -> new IllegalStateException("Run not found: " + runId));
            Supplier<SuiteSnapshotDto> snapshot = lazySnapshot(run);

            if (!skipDeploymentPhase) {
                // Inconsistent snapshot guard
                boolean hasSnapshot = run.getSuiteSnapshot() != null;
                boolean hasInputs = testCaseRunInputRepository.existsByRunId(runId);
                if (hasSnapshot != hasInputs) {
                    log.error(
                            "Inconsistent snapshot state for run {}: suite_snapshot={}, inputs={}",
                            runId,
                            hasSnapshot,
                            hasInputs);
                    now = clock.millis();
                    String errorDetails = buildErrorDetails(
                            "SNAPSHOT_STATE_INCONSISTENT",
                            RunErrorCategory.INTERNAL,
                            "Exactly one of suite_snapshot / test_case_run_inputs is present",
                            null);
                    int affected =
                            repository.updateToFailed(runId, "Inconsistent snapshot state", errorDetails, now, now);
                    statusChanged = affected > 0;
                    return;
                }

                handle.throwIfCancelled();
                // Phase 1: Deployment evaluation
                EvaluationContext context = buildContext(run, snapshot.get(), handle.executor(), token);
                evaluationExecutor.execute(context);
            }

            handle.throwIfCancelled();
            // Phase 2: Metric evaluation
            MetricEvaluationContext metricContext =
                    buildMetricEvaluationContext(run, snapshot.get(), handle.executor());
            metricEvaluationExecutor.execute(metricContext);

            handle.throwIfCancelled();
            // Phase 3: Metric score statistics — reuses Phase 2's computationId. Non-fatal: a
            // failure here must not fail an otherwise-good run (scores are regenerable).
            computeMetricScores(run, snapshot.get(), metricContext);

            now = clock.millis();
            if (repository.updateToCompleted(runId, now, now) == 0) {
                int affected = repository.updateToCancelled(runId, now, now);
                statusChanged = affected > 0;
                if (affected > 0) {
                    log.info("Run {} cancelled", runId);
                } else {
                    log.info("Run {} was already terminal when marking it cancelled", runId);
                }
            } else {
                statusChanged = true;
                log.info("Run {} completed", runId);
            }
        } catch (AnalyticsWriteException e) {
            log.error("Analytics batch write failed for run {}: {}", runId, e.getMessage(), e);
            long now = clock.millis();
            String errorDetails = buildErrorDetails(
                    AnalyticsWriteException.ERROR_CODE,
                    RunErrorCategory.INTERNAL,
                    "Analytics batch write failed",
                    null);
            int affected = repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now);
            statusChanged = affected > 0;
        } catch (CancellationException | RejectedExecutionException e) {
            // Clear a stray interrupt flag before the terminal write: the job thread is interrupted only
            // when SimpleAsyncTaskExecutor.close() runs at container shutdown, and an interrupted thread
            // would fail Hikari connection acquisition for this write.
            Thread.interrupted();
            log.info("Run {} cancelled: {}", runId, e.getMessage(), e);
            long now = clock.millis();
            int affected = repository.updateToCancelled(runId, now, now);
            statusChanged = affected > 0;
        } catch (Exception e) {
            // Clear a stray interrupt flag before the terminal write — see comment in the catch above.
            Thread.interrupted();
            long now = clock.millis();
            if (handle.isCancelled()) {
                log.info("Run {} cancelled, suppressing failure: {}", runId, e.getMessage(), e);
                int affected = repository.updateToCancelled(runId, now, now);
                statusChanged = affected > 0;
            } else {
                log.error("Run {} failed unexpectedly: {}", runId, e.getMessage(), e);
                String errorDetails = buildErrorDetails(
                        "UNEXPECTED_ERROR",
                        RunErrorCategory.INTERNAL,
                        "An unexpected error occurred during execution",
                        null);
                int affected = repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now);
                statusChanged = affected > 0;
            }
        } finally {
            handle.close();
            // registry.remove(runId) must be the LAST statement: notifySse performs a DB read
            // (repository.findById), and the functional-test drain waits on registry.activeCount() == 0
            // before tearing down its schema, so the handle must stay registered until every DB access
            // this method makes has completed.
            if (statusChanged) {
                notifySse(runId);
            }
            registry.remove(runId);
        }
    }

    /** Outcome of {@link #executeSnapshotPhase}, distinguishing success from the two ways it can fail —
     * whether the terminal FAILED write it performed actually affected a row (vs. the run already
     * being terminal via a concurrent cancel) — so the caller knows whether to emit a status-update SSE. */
    private enum SnapshotPhaseOutcome {
        SUCCESS,
        FAILED_ROW_UPDATED,
        FAILED_NO_ROW_UPDATED
    }

    /**
     * Executes the snapshot phase with retry on serialization failures.
     */
    private SnapshotPhaseOutcome executeSnapshotPhase(UUID runId, boolean captureTestCaseInputs) {
        for (int attempt = 0; attempt <= SNAPSHOT_MAX_RETRIES; attempt++) {
            try {
                attemptSnapshot(runId, captureTestCaseInputs);
                return SnapshotPhaseOutcome.SUCCESS;
            } catch (Exception e) {
                String sqlState = extractSqlState(e);
                if (SQLSTATE_SERIALIZATION_FAILURE.equals(sqlState) && attempt < SNAPSHOT_MAX_RETRIES) {
                    log.warn(
                            "Snapshot serialization conflict for run {} (attempt {}), retrying", runId, attempt + 1, e);
                    continue;
                }
                log.error("Snapshot phase failed for run {} (attempt {})", runId, attempt + 1, e);
                long now = clock.millis();
                String code = SQLSTATE_SERIALIZATION_FAILURE.equals(sqlState)
                        ? "SNAPSHOT_SERIALIZATION_CONFLICT"
                        : resolveSnapshotErrorCode(e);
                String errorDetails = buildErrorDetails(
                        code, RunErrorCategory.INTERNAL, "Snapshot phase failed: " + e.getMessage(), null);
                int affected = repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now);
                if (affected == 0) {
                    log.info("Run {} was already terminal when the snapshot phase attempted to mark it FAILED", runId);
                    return SnapshotPhaseOutcome.FAILED_NO_ROW_UPDATED;
                }
                return SnapshotPhaseOutcome.FAILED_ROW_UPDATED;
            }
        }
        return SnapshotPhaseOutcome.FAILED_NO_ROW_UPDATED;
    }

    private void attemptSnapshot(UUID runId, boolean captureTestCaseInputs) {
        TransactionTemplate tx = new TransactionTemplate(metaTransactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(status -> {
            // Delete any leftover inputs from a prior failed attempt
            if (testCaseRunInputRepository
                    instanceof
                    com.epam.aidial.evaluation.data.db.repository.PostgresTestCaseRunInputRepository pgRepo) {
                pgRepo.deleteByRunId(runId);
            }

            TestSuite suite = testSuiteRepository
                    .findById(repository
                            .findById(runId)
                            .orElseThrow(() -> new IllegalStateException("Run not found: " + runId))
                            .getTestSuiteId())
                    .orElseThrow(() -> new SnapshotSuiteMissingException("Suite not found for run: " + runId));

            Dataset dataset = datasetRepository
                    .findById(suite.getDatasetId())
                    .orElseThrow(() -> new SnapshotDatasetMissingException(
                            "Dataset not found for run: " + runId + ", datasetId=" + suite.getDatasetId()));

            SuiteSnapshotDto snapshot = suiteSnapshotBuilder.build(suite, dataset);
            String snapshotJson;
            try {
                snapshotJson = objectMapper.writeValueAsString(snapshot);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialize suite snapshot", e);
            }

            long now = clock.millis();
            repository.updateSuiteSnapshot(runId, snapshotJson, now);

            if (!captureTestCaseInputs) {
                log.info("Created suite snapshot for run {} (no test case inputs captured)", runId);
                return null;
            }

            List<TestCaseRunInput> batch = new ArrayList<>();
            int position = 0;
            int offset = 0;
            List<TestCase> page;
            do {
                page = runnableTestCaseSelector.loadRunnablePage(
                        suite.getDatasetId(), suite.getTestCaseFilter(), offset, SNAPSHOT_PAGE_SIZE);
                for (TestCase tc : page) {
                    batch.add(TestCaseRunInput.builder()
                            .runId(runId)
                            .position(position++)
                            .testCaseId(tc.getId())
                            .testCaseName(tc.getTestCaseName())
                            .testCaseData(tc.getData())
                            .multiTurnData(tc.getMultiTurnData())
                            .build());
                }
                if (!batch.isEmpty()) {
                    testCaseRunInputRepository.insertBatch(batch);
                    batch.clear();
                }
                offset += SNAPSHOT_PAGE_SIZE;
            } while (page.size() == SNAPSHOT_PAGE_SIZE);

            int totalInputs = position;
            repository.updateNumberOfTestCases(runId, totalInputs, clock.millis());
            log.info("Created suite snapshot for run {}: {} test case input(s)", runId, totalInputs);
            return null;
        });
    }

    private String resolveSnapshotErrorCode(Exception e) {
        if (e instanceof SnapshotSuiteMissingException) {
            return "SNAPSHOT_SUITE_MISSING";
        }
        if (e instanceof SnapshotDatasetMissingException) {
            return "SNAPSHOT_DATASET_MISSING";
        }
        return "SNAPSHOT_FAILED";
    }

    private String extractSqlState(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    private MetricEvaluationContext buildMetricEvaluationContext(
            TestSuiteRun run, SuiteSnapshotDto snapshot, ExecutorService executor) {
        List<AggregatedMetricDefinition> tsmds =
                testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(run.getTestSuiteId());

        return MetricEvaluationContext.builder()
                .computationId(UUID.randomUUID())
                .computedAtMs(clock.millis())
                .testSuiteRunId(run.getId())
                .testSuiteId(run.getTestSuiteId())
                .runCreatedAtMs(run.getCreatedAt())
                .aggregatedTsmds(tsmds)
                .executor(executor)
                .retryConfig(metricEvaluationProperties.getRetry())
                .defaultConcurrencyPerProvider(metricEvaluationProperties.getDefaultConcurrencyPerProvider())
                .batchSize(metricEvaluationProperties.getBatchSize())
                .perResultTimeoutMs(metricEvaluationProperties.getPerResultTimeoutMs())
                .requestLabels(buildRequestLabels(snapshot))
                // Per-test-case scoring prefers testCaseOverallScore when the suite configured one,
                // falling back to overallScore otherwise. The run-level aggregate (computeMetricScores,
                // below) always uses overallScore unconditionally — the two scopes may diverge.
                .overallScoreDefinition(
                        snapshot.getTestCaseOverallScore() != null
                                ? snapshot.getTestCaseOverallScore()
                                : snapshot.getOverallScore())
                .overallScoreThreshold(snapshot.getOverallScoreThreshold())
                .build();
    }

    /**
     * Builds the chain's ordered request-label list: {@code snapshot.requestName} at index 0, then
     * each {@code additionalRequests[i].name} in chain order. Consumed by
     * {@link MetricEvaluationContext#requestLabelAt(int)} so Phase 2 can resolve a result row's
     * {@code request.name} by {@code requestIndex} without a new analytics column.
     */
    private List<String> buildRequestLabels(SuiteSnapshotDto snapshot) {
        List<String> labels = new ArrayList<>();
        labels.add(snapshot.getRequestName());
        List<RequestDefinitionDto> additionalRequests = snapshot.getAdditionalRequests();
        if (additionalRequests != null) {
            for (RequestDefinitionDto request : additionalRequests) {
                labels.add(request != null ? request.getName() : null);
            }
        }
        return labels;
    }

    /**
     * Phase 3: computes aggregated metric-score statistics, reusing the metric-evaluation
     * {@code computationId} so the scores join that computation. Non-fatal — any failure is logged and
     * the run still completes, because scores are a regenerable projection over the eval summaries.
     */
    private void computeMetricScores(
            TestSuiteRun run, SuiteSnapshotDto snapshot, MetricEvaluationContext metricContext) {
        try {
            MetricScoreComputationContext ctx = MetricScoreComputationContext.builder()
                    .testSuiteRunId(run.getId())
                    .testSuiteId(run.getTestSuiteId())
                    .computationId(metricContext.getComputationId())
                    .overallScoreDefinition(snapshot.getOverallScore())
                    .computedAtMs(clock.millis())
                    .build();
            metricScoreComputation.execute(ctx);
        } catch (RuntimeException e) {
            log.error(
                    "Metric score computation failed for run {}; run will still complete: {}",
                    run.getId(),
                    e.getMessage(),
                    e);
        }
    }

    private EvaluationContext buildContext(
            TestSuiteRun run, SuiteSnapshotDto snapshot, ExecutorService executor, String token) {
        RunConfigDto config = parseRunConfig(run.getRunConfig(), run.getId());
        EvaluationRunProperties.Execution execProps = evaluationRunProperties.getExecution();
        EvaluationRunProperties.Retry retryProps = evaluationRunProperties.getRetry();

        ExecutionSettingsDto exec = config.getExecution();
        RetryPolicyDto retry = config.getRetry();

        SuiteType suiteType =
                snapshot.getSuiteType() != null ? SuiteType.valueOf(snapshot.getSuiteType()) : SuiteType.DEPLOYMENT;

        UUID datasetId =
                snapshot.getDatasetRef() != null ? snapshot.getDatasetRef().getId() : null;

        return EvaluationContext.builder()
                .runId(run.getId())
                .suiteId(run.getTestSuiteId())
                .datasetId(datasetId)
                .numberOfRuns(config.getNumberOfRuns())
                .numberOfTestCases(run.getNumberOfTestCases())
                .concurrencyLevel(ObjectUtils.getIfNull(
                        exec != null ? exec.getConcurrencyLevel() : null, execProps.getDefaultConcurrencyLevel()))
                .requestTimeoutMs(ObjectUtils.getIfNull(
                        exec != null ? exec.getRequestTimeoutMs() : null, execProps.getDefaultRequestTimeoutMs()))
                .rateLimitRps(exec != null ? exec.getRateLimitRps() : execProps.getDefaultRateLimitRps())
                .maxRetries(ObjectUtils.getIfNull(
                        retry != null ? retry.getMaxRetries() : null, retryProps.getDefaultMaxRetries()))
                .retryDelayMs(ObjectUtils.getIfNull(
                        retry != null ? retry.getRetryDelayMs() : null, retryProps.getDefaultRetryDelayMs()))
                .retryBackoffMultiplier(ObjectUtils.getIfNull(
                        retry != null ? retry.getRetryBackoffMultiplier() : null,
                        retryProps.getDefaultRetryBackoffMultiplier()))
                .maxRetryDelayMs(retryProps.getMaxRetryDelayMs())
                .resultBatchSize(execProps.getResultBatchSize())
                .maxResponseSizeBytes(execProps.getMaxResponseSizeBytes())
                .executor(executor)
                .token(token)
                .createdAtMs(run.getCreatedAt())
                .suiteType(suiteType)
                .snapshotDeploymentRef(snapshot.getDeploymentRef())
                .snapshotEndpointRef(snapshot.getEndpointRef())
                .snapshotRequestTemplate(snapshot.getRequestTemplate())
                .snapshotInputBindings(snapshot.getInputBindings())
                .snapshotResponseColumns(snapshot.getResponseColumns())
                .snapshotAdditionalRequests(snapshot.getAdditionalRequests())
                .snapshotRequestName(snapshot.getRequestName())
                .snapshotTestCaseSchema(snapshot.getTestCaseSchema())
                .mcpDeploymentRefDto(snapshot.getMcpDeploymentRef())
                .toolRefDto(snapshot.getToolRef())
                .argumentTemplateDto(snapshot.getArgumentTemplate())
                .inputBindings(snapshot.getInputBindings())
                .build();
    }

    /**
     * Wraps {@link #resolveSnapshot(TestSuiteRun)} so all three phases share one resolution: parsing the
     * snapshot JSON — and, for legacy snapshot-less runs, re-fetching the live suite + dataset — is pure
     * waste on every repeat.
     *
     * <p>Deliberately lazy rather than resolved eagerly at the call site: resolution can throw (bad JSON,
     * unsupported snapshot version, suite/dataset gone), and hoisting it above the inconsistent-snapshot
     * guard would report those as a generic {@code UNEXPECTED_ERROR} instead of the specific
     * {@code SNAPSHOT_STATE_INCONSISTENT}. Single-threaded per run, so the memoization needs no
     * synchronization.
     */
    private Supplier<SuiteSnapshotDto> lazySnapshot(TestSuiteRun run) {
        return new Supplier<>() {
            private SuiteSnapshotDto resolved;

            @Override
            public SuiteSnapshotDto get() {
                if (resolved == null) {
                    resolved = resolveSnapshot(run);
                }
                return resolved;
            }
        };
    }

    private SuiteSnapshotDto resolveSnapshot(TestSuiteRun run) {
        String snapshotJson = run.getSuiteSnapshot();
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            try {
                SuiteSnapshotDto snapshot = objectMapper.readValue(snapshotJson, SuiteSnapshotDto.class);
                String version = snapshot.getSnapshotVersion() != null
                        ? snapshot.getSnapshotVersion()
                        : SuiteSnapshotDto.CURRENT_VERSION;
                if (!SuiteSnapshotDto.CURRENT_VERSION.equals(version)) {
                    log.warn("Unsupported snapshot version '{}' for run {}", version, run.getId());
                    throw new UnsupportedSnapshotVersionException("Unsupported snapshot version: " + version);
                }
                return snapshot;
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to deserialize suite_snapshot for run " + run.getId(), e);
            }
        }

        // Legacy run (no stored snapshot): synthesize a transient snapshot from the live
        // (suite, dataset) pair. Both must still exist; otherwise the run fails with the
        // appropriate structured error code so callers can distinguish "suite gone" from
        // "dataset gone".
        TestSuite suite = testSuiteRepository
                .findById(run.getTestSuiteId())
                .orElseThrow(() ->
                        new SnapshotSuiteMissingException("Suite not found for legacy run: " + run.getTestSuiteId()));
        Dataset dataset = datasetRepository
                .findById(suite.getDatasetId())
                .orElseThrow(() -> new SnapshotDatasetMissingException("Dataset not found for legacy run: run="
                        + run.getId() + ", datasetId=" + suite.getDatasetId()));
        return suiteSnapshotBuilder.build(suite, dataset);
    }

    private RunConfigDto parseRunConfig(String runConfigJson, UUID runId) {
        if (runConfigJson == null || runConfigJson.isBlank()) {
            return RunConfigDto.builder().numberOfRuns(1).build();
        }
        try {
            return objectMapper.readValue(runConfigJson, RunConfigDto.class);
        } catch (JacksonException e) {
            log.warn("Failed to parse runConfig for run {}: {}", runId, e.getMessage(), e);
            return RunConfigDto.builder().numberOfRuns(1).build();
        }
    }

    private void notifySse(UUID runId) {
        try {
            repository.findById(runId).ifPresent(sseService::notifyStatusUpdate);
        } catch (Exception e) {
            log.warn("Failed to send SSE notification for run {}", runId, e);
        }
    }

    public String buildErrorDetails(
            String code, RunErrorCategory category, String message, Map<String, Object> details) {
        RunErrorDetailsDto dto = RunErrorDetailsDto.builder()
                .code(code)
                .category(category.name())
                .message(message)
                .details(details)
                .build();
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JacksonException ex) {
            log.error("Failed to serialize error details", ex);
            return null;
        }
    }
}
