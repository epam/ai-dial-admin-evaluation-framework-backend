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
import com.epam.aidial.evaluation.runner.job.InlineMetricEvaluator;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.SuiteSnapshotBuilder;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorCategory;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotDatasetMissingException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
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
    private final RunMetricSnapshotBatchWriteClient runMetricSnapshotBatchWriteClient;
    private final MetricScoreComputationExecutor metricScoreComputation;
    private final InlineModeDetector inlineModeDetector;
    private final InlineMetricEvaluatorFactory inlineMetricEvaluatorFactory;
    private final Clock clock;

    @Qualifier("metaTransactionManager")
    private final PlatformTransactionManager metaTransactionManager;

    private final ConcurrentHashMap<UUID, AtomicBoolean> activeCancellationSignals = new ConcurrentHashMap<>();

    /**
     * Registers a cancellation signal for the given run BEFORE async dispatch.
     * Must be called in the caller's thread to prevent race conditions.
     */
    public void registerCancellationSignal(UUID runId) {
        activeCancellationSignals.put(runId, new AtomicBoolean(false));
    }

    /**
     * Removes the cancellation signal for the given run.
     * Used for cleanup if async dispatch fails.
     */
    public void removeCancellationSignal(UUID runId) {
        activeCancellationSignals.remove(runId);
    }

    public void interruptRun(UUID runId) {
        AtomicBoolean signal = activeCancellationSignals.get(runId);
        if (signal != null) {
            signal.set(true);
        }
    }

    @Async("testSuiteRunExecutor")
    public void executeRunAsync(UUID runId, String token, boolean skipDeploymentPhase) {
        final AtomicBoolean cancellationSignal =
                activeCancellationSignals.computeIfAbsent(runId, _ -> new AtomicBoolean(false));
        // Non-null only for an inline run's normal branch — kept at method scope so the existing
        // `finally` below can call close() as a no-op safety net regardless of how this method exits
        // (design.md Decision 5's "Flush timing").
        InlineMetricEvaluatorImpl inlineEvaluator = null;
        try {
            log.info("Starting test suite run {}", runId);
            long now = clock.millis();
            if (cancellationSignal.get()) {
                log.info("Run {} cancelled before start", runId);
                repository.updateToCancelled(runId, now, now);
                notifySse(runId);
                return;
            }

            if (!executeSnapshotPhase(runId, !skipDeploymentPhase)) {
                return;
            }

            now = clock.millis();
            repository.updateToRunning(runId, now, now);
            notifySse(runId);

            TestSuiteRun run =
                    repository.findById(runId).orElseThrow(() -> new IllegalStateException("Run not found: " + runId));
            Supplier<SuiteSnapshotDto> snapshot = lazySnapshot(run);

            MetricEvaluationContext metricContext;
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
                    repository.updateToFailed(runId, "Inconsistent snapshot state", errorDetails, now, now);
                    notifySse(runId);
                    return;
                }

                // Metric context + snapshot must be built here, still inside the guard: a run that
                // fails the guard above returns before this point and writes no run_metric_snapshots row.
                // Only this branch ever runs the InlineModeDetector (detectInline = true) — the
                // skipDeploymentPhase branch below always forces inlineMode = false.
                metricContext = buildMetricContextAndWriteSnapshot(run, snapshot, cancellationSignal, true);

                if (metricContext.isInlineMode()) {
                    inlineEvaluator = inlineMetricEvaluatorFactory.create(metricContext);
                }

                // Phase 1: Deployment evaluation
                EvaluationContext context =
                        buildContext(run, snapshot.get(), cancellationSignal, token, inlineEvaluator);
                evaluationExecutor.execute(context);

                // The inline evaluator's buffered EvalSummary writes must be flushed before Phase 2/3
                // reads test_case_eval_summaries — otherwise Phase 3's score aggregation would see a
                // truncated set for any inline run whose last batch had not yet been flushed
                // (design.md Decision 5's "Flush timing").
                if (inlineEvaluator != null) {
                    inlineEvaluator.flush();
                }
            } else {
                // No inconsistency guard and no Phase 1 to run on the import path: build the metric
                // context and write its snapshot unconditionally, once, right here. detectInline = false
                // — the detector is never invoked for this path, so the context is always non-inline.
                metricContext = buildMetricContextAndWriteSnapshot(run, snapshot, cancellationSignal, false);
            }

            // Phase 2: Metric evaluation
            if (!cancellationSignal.get()) {
                metricEvaluationExecutor.execute(metricContext);

                // Phase 3: Metric score statistics — reuses Phase 2's computationId. Non-fatal: a
                // failure here must not fail an otherwise-good run (scores are regenerable).
                if (!cancellationSignal.get()) {
                    computeMetricScores(run, snapshot.get(), metricContext, cancellationSignal);
                }
            }

            now = clock.millis();
            if (cancellationSignal.get()) {
                log.info("Run {} cancelled", runId);
                repository.updateToCancelled(runId, now, now);
            } else {
                log.info("Run {} completed", runId);
                repository.updateToCompleted(runId, now, now);
            }
            notifySse(runId);

        } catch (Exception e) {
            log.error("Run failed unexpectedly: {}", runId, e);
            long now = clock.millis();
            String errorDetails = buildErrorDetails(
                    "UNEXPECTED_ERROR",
                    RunErrorCategory.INTERNAL,
                    "An unexpected error occurred during execution",
                    null);
            repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now);
            notifySse(runId);
        } finally {
            // Safety net: normally a no-op, since the inline evaluator (if any) already flushed above.
            // Also releases its dedicated virtual-thread executor on every exit path.
            if (inlineEvaluator != null) {
                inlineEvaluator.close();
            }
            activeCancellationSignals.remove(runId);
        }
    }

    /**
     * Executes the snapshot phase with retry on serialization failures.
     * Returns true on success, false if the run was marked FAILED.
     */
    private boolean executeSnapshotPhase(UUID runId, boolean captureTestCaseInputs) {
        for (int attempt = 0; attempt <= SNAPSHOT_MAX_RETRIES; attempt++) {
            try {
                attemptSnapshot(runId, captureTestCaseInputs);
                return true;
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
                repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now);
                notifySse(runId);
                return false;
            }
        }
        return false;
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

    /**
     * Builds the {@link MetricEvaluationContext} (TSMD load + fresh {@code computationId}/
     * {@code computedAtMs}) and immediately writes its {@code run_metric_snapshots} row — the two are
     * always done together so every computation that gets a snapshot row also gets a matching context,
     * and vice versa. Called from both branches of {@link #executeRunAsync}, on the normal branch only
     * after the inconsistent-snapshot guard passes (see the call site), so a run failing that guard
     * writes no {@code run_metric_snapshots} row.
     *
     * @param detectInline whether {@link InlineModeDetector} should run at all — {@code true} only on
     *                      the normal ({@code !skipDeploymentPhase}) branch; the {@code
     *                      skipDeploymentPhase} branch passes {@code false} so the resulting context's
     *                      {@code inlineMode} is always {@code false}, without the detector ever being
     *                      invoked (per the "Inline metric evaluation mode is derived per run"
     *                      requirement's {@code skipDeploymentPhase ⇒ non-inline} rule).
     */
    private MetricEvaluationContext buildMetricContextAndWriteSnapshot(
            TestSuiteRun run,
            Supplier<SuiteSnapshotDto> snapshot,
            AtomicBoolean cancellationSignal,
            boolean detectInline) {
        MetricEvaluationContext metricContext =
                buildMetricEvaluationContext(run, snapshot.get(), cancellationSignal, detectInline);
        writeRunMetricSnapshots(metricContext);
        return metricContext;
    }

    private MetricEvaluationContext buildMetricEvaluationContext(
            TestSuiteRun run, SuiteSnapshotDto snapshot, AtomicBoolean cancellationSignal, boolean detectInline) {
        List<AggregatedMetricDefinition> tsmds =
                testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(run.getTestSuiteId());
        boolean inlineMode = detectInline && inlineModeDetector.isInline(snapshot, tsmds);

        return MetricEvaluationContext.builder()
                .computationId(UUID.randomUUID())
                .computedAtMs(clock.millis())
                .testSuiteRunId(run.getId())
                .testSuiteId(run.getTestSuiteId())
                .runCreatedAtMs(run.getCreatedAt())
                .aggregatedTsmds(tsmds)
                .cancellationSignal(cancellationSignal)
                .retryConfig(metricEvaluationProperties.getRetry())
                .defaultConcurrencyPerProvider(metricEvaluationProperties.getDefaultConcurrencyPerProvider())
                .batchSize(metricEvaluationProperties.getBatchSize())
                .perResultTimeoutMs(metricEvaluationProperties.getPerResultTimeoutMs())
                .requestLabels(buildRequestLabels(snapshot))
                .inlineMode(inlineMode)
                .build();
    }

    /**
     * Writes one {@code run_metric_snapshots} row per aggregated TSMD, capturing each TSMD's bindings
     * and output schema as of this computation. Moved here from
     * {@code InProcessMetricEvaluationExecutor.execute()} so the snapshot is written once, before Phase
     * 1, rather than at the start of Phase 2 — see {@link #buildMetricContextAndWriteSnapshot}.
     */
    private void writeRunMetricSnapshots(MetricEvaluationContext context) {
        List<RunMetricSnapshotBatchWriteItemDto> snapshots = context.getAggregatedTsmds().stream()
                .map(this::buildSnapshotItem)
                .toList();

        runMetricSnapshotBatchWriteClient.batchWrite(
                context.getTestSuiteRunId(), context.getComputationId(), context.getComputedAtMs(), snapshots);

        log.debug(
                "Wrote {} RunMetricSnapshots for run {}, computationId={}",
                snapshots.size(),
                context.getTestSuiteRunId(),
                context.getComputationId());
    }

    private RunMetricSnapshotBatchWriteItemDto buildSnapshotItem(AggregatedMetricDefinition tsmd) {
        return RunMetricSnapshotBatchWriteItemDto.builder()
                .tsmdId(tsmd.getId())
                .tsmdName(tsmd.getName())
                .metricDeclarationId(tsmd.getMetricDeclarationId())
                .metricDeclarationVersionId(tsmd.getMetricDeclarationVersionId())
                .configBindings(parseJsonNode(tsmd.getConfigBindings()))
                .inputBindings(parseJsonNode(tsmd.getInputBindings()))
                .outputSchema(parseJsonNode(tsmd.getVersionOutputSchema()))
                .build();
    }

    private JsonNode parseJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            log.warn("Failed to parse JSON: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
        }
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
            TestSuiteRun run,
            SuiteSnapshotDto snapshot,
            MetricEvaluationContext metricContext,
            AtomicBoolean cancellationSignal) {
        try {
            MetricScoreComputationContext ctx = MetricScoreComputationContext.builder()
                    .testSuiteRunId(run.getId())
                    .testSuiteId(run.getTestSuiteId())
                    .computationId(metricContext.getComputationId())
                    .overallScoreDefinition(snapshot.getOverallScore())
                    .computedAtMs(clock.millis())
                    .cancellationSignal(cancellationSignal)
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
            TestSuiteRun run,
            SuiteSnapshotDto snapshot,
            AtomicBoolean cancellationSignal,
            String token,
            InlineMetricEvaluator inlineMetricEvaluator) {
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
                .cancellationGracePeriodMs(execProps.getCancellationGracePeriodMs())
                .cancellationSignal(cancellationSignal)
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
                .inlineMetricEvaluator(inlineMetricEvaluator)
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
