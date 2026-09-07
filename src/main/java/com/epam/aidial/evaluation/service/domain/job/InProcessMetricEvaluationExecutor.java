package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.query.service.metricscore.EvalSummaryRowScoreComputer;
import com.epam.aidial.evaluation.query.service.metricscore.MetricField;
import com.epam.aidial.evaluation.query.service.metricscore.MetricFieldDiscoverer;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.ConditionContext;
import com.epam.aidial.evaluation.service.domain.ConditionDecision;
import com.epam.aidial.evaluation.service.domain.ConditionExpressionEvaluator;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.analytics.TestCaseEvalScoreService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseEvalScoreBatchWriteItemDto;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-process metric evaluation executor using virtual threads bounded by provider semaphores.
 * Iterates results sequentially via cursor pagination, dispatches TSMD evaluations in parallel
 * on a shared executor, captures RunMetricSnapshots, and buffers EvalSummary records
 * for batch writing.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class InProcessMetricEvaluationExecutor implements MetricEvaluationExecutor {

    private static final int RESULT_PAGE_SIZE = 100;

    private final TestCaseRunResultRepository resultRepository;
    private final MetricEvaluationWorker worker;
    private final MetricOutputMapper outputMapper;
    private final EvalSummaryBatchWriteClient evalSummaryBatchWriteClient;
    private final RunMetricSnapshotBatchWriteClient runMetricSnapshotBatchWriteClient;
    private final ObjectMapper objectMapper;
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;
    private final ConditionExpressionEvaluator conditionExpressionEvaluator;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
    private final MetricFieldDiscoverer metricFieldDiscoverer;
    private final EvalSummaryRowScoreComputer evalSummaryRowScoreComputer;
    private final TestCaseEvalScoreService testCaseEvalScoreService;
    private final Clock clock;

    @Override
    public void execute(MetricEvaluationContext context) {
        if (context.getAggregatedTsmds().isEmpty()) {
            // A metric-less run still writes one eval summary per result row (empty metric_values,
            // no metric_infos, no run metric snapshots), so its responses and extracted columns
            // remain readable through the eval-summary endpoints. Everything below degenerates
            // correctly on an empty TSMD list — no separate metric-less branch.
            log.info("No TSMDs in context for run {}, writing metric-less eval summaries", context.getTestSuiteRunId());
        }

        log.info(
                "Starting metric evaluation for run {}, {} TSMDs",
                context.getTestSuiteRunId(),
                context.getAggregatedTsmds().size());

        writeRunMetricSnapshots(context);
        List<String> metricFieldNames = discoverMetricFieldNames(context);

        Map<String, Semaphore> providerSemaphores = buildProviderSemaphores(context);
        List<FilterCondition> filters = buildRunIdFilters(context);
        List<EvalSummaryBatchWriteItemDto> buffer = new ArrayList<>();
        Cursor cursor = null;

        ExecutorService executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
        try {
            do {
                if (context.getCancellationSignal().get()) {
                    log.info("Metric evaluation cancelled for run {}", context.getTestSuiteRunId());
                    break;
                }

                CursorPage<TestCaseRunResult> page =
                        resultRepository.findAll(filters, context.getRunCreatedAtMs(), cursor, RESULT_PAGE_SIZE);

                for (TestCaseRunResult result : page.content()) {
                    if (context.getCancellationSignal().get()) {
                        break;
                    }

                    log.debug(
                            "Run {}: evaluating metrics for result {} (testCaseId={}, status={})",
                            context.getTestSuiteRunId(),
                            result.getId(),
                            result.getTestCaseId(),
                            result.getExecutionStatus());

                    if (result.getExecutionStatus() != ExecutionStatus.SUCCESS) {
                        buffer.add(buildPropagatedItem(result, context));
                    } else {
                        buffer.add(evaluateAndBuild(result, context, providerSemaphores, executor));
                    }
                }

                flushIfNeeded(buffer, context, metricFieldNames);
                cursor = page.nextCursor();
            } while (cursor != null);
        } finally {
            flushRemaining(buffer, context, metricFieldNames);
            executor.shutdownNow();
        }

        log.info("Metric evaluation completed for run {}", context.getTestSuiteRunId());
    }

    private List<FilterCondition> buildRunIdFilters(MetricEvaluationContext context) {
        FilterCondition runIdFilter = FilterCondition.builder()
                .field("runId")
                .operator(FilterOperator.EQ)
                .rawValue(context.getTestSuiteRunId().toString())
                .build();
        return List.of(runIdFilter);
    }

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

    /**
     * The run's discovered numeric metric field names, read back once via the same
     * {@link MetricFieldDiscoverer} Phase 3 uses — so a {@code Mean} overall score's divisor can never
     * disagree between the two phases for the same run. One query per {@link #execute} call, not per flush.
     */
    private List<String> discoverMetricFieldNames(MetricEvaluationContext context) {
        List<RunMetricSnapshot> snapshots = runMetricSnapshotRepository.findByRunIdAndComputationId(
                context.getTestSuiteRunId(), context.getComputationId());
        return metricFieldDiscoverer.discover(snapshots).stream()
                .map(MetricField::flattenedName)
                .toList();
    }

    private Map<String, Semaphore> buildProviderSemaphores(MetricEvaluationContext context) {
        Map<String, Semaphore> semaphores = new HashMap<>();
        for (AggregatedMetricDefinition tsmd : context.getAggregatedTsmds()) {
            semaphores.computeIfAbsent(
                    tsmd.getDeclarationProviderId(), k -> new Semaphore(context.getDefaultConcurrencyPerProvider()));
        }
        return semaphores;
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

    private EvalSummaryBatchWriteItemDto evaluateAndBuild(
            TestCaseRunResult result,
            MetricEvaluationContext context,
            Map<String, Semaphore> providerSemaphores,
            ExecutorService executor) {
        // Pre-extract output field names before async dispatch so they are available in Failure results
        Map<String, List<String>> outputFieldNamesMap = context.getAggregatedTsmds().stream()
                .collect(Collectors.toMap(
                        AggregatedMetricDefinition::getName,
                        tsmd -> outputSchemaFieldExtractor.extractFieldNames(tsmd.getVersionOutputSchema())));

        Map<String, TsmdEvaluationResult> tsmdResults = new ConcurrentHashMap<>();

        // Evaluate each metric's condition synchronously (before async dispatch) over
        // {data, response, turn, request}: RUN → dispatch; SKIP → omit the metric entirely; ERROR →
        // record a metric-level ConditionError (row stays SUCCESS). Only dispatched metrics are
        // reconciled for timeout/failure below.
        ConditionContext conditionContext = ConditionContext.builder()
                .dataJson(result.getTestCaseData())
                .responseJson(result.getExtractedColumns())
                .turnIndex(result.getTurnIndex())
                .totalTurns(result.getTotalTurns())
                .requestIndex(result.getRequestIndex())
                .totalRequests(result.getTotalRequests())
                .requestName(context.requestLabelAt(result.getRequestIndex()))
                .build();

        List<AggregatedMetricDefinition> dispatchedTsmds = new ArrayList<>();
        for (AggregatedMetricDefinition tsmd : context.getAggregatedTsmds()) {
            ConditionDecision decision = conditionExpressionEvaluator.evaluate(tsmd.getCondition(), conditionContext);
            if (decision.isSkip()) {
                continue;
            }
            if (decision.isError()) {
                tsmdResults.put(
                        tsmd.getName(),
                        new TsmdEvaluationResult.ConditionError(
                                decision.errorMessage(), outputFieldNamesMap.get(tsmd.getName())));
                continue;
            }
            dispatchedTsmds.add(tsmd);
        }

        Map<String, Long> dispatchStartedAtMsByTsmd = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> tsmdFutures = new ArrayList<>();
        for (AggregatedMetricDefinition tsmd : dispatchedTsmds) {
            List<String> fieldNames = outputFieldNamesMap.get(tsmd.getName());
            Semaphore semaphore = providerSemaphores.get(tsmd.getDeclarationProviderId());
            log.debug(
                    "Run {}: dispatching metric '{}' for result {}",
                    context.getTestSuiteRunId(),
                    tsmd.getName(),
                    result.getId());
            dispatchStartedAtMsByTsmd.put(tsmd.getName(), clock.millis());
            CompletableFuture<Void> tsmdFuture = CompletableFuture.runAsync(
                    () -> {
                        long startedAtMs = clock.millis();
                        try {
                            EvaluationResponseDto response = worker.evaluate(tsmd, result, semaphore, context);
                            tsmdResults.put(
                                    tsmd.getName(),
                                    new TsmdEvaluationResult.Success(
                                            response, fieldNames, clock.millis() - startedAtMs));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn(
                                    "Metric evaluation interrupted for TSMD {} on result {}",
                                    tsmd.getName(),
                                    result.getId(),
                                    e);
                            tsmdResults.put(
                                    tsmd.getName(),
                                    new TsmdEvaluationResult.Failure(e, fieldNames, clock.millis() - startedAtMs));
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Metric evaluation failed for TSMD {} on result {}: {}",
                                    tsmd.getName(),
                                    result.getId(),
                                    e.getMessage(),
                                    e);
                            tsmdResults.put(
                                    tsmd.getName(),
                                    new TsmdEvaluationResult.Failure(e, fieldNames, clock.millis() - startedAtMs));
                        }
                    },
                    executor);

            tsmdFutures.add(tsmdFuture);
        }

        try {
            CompletableFuture.allOf(tsmdFutures.toArray(new CompletableFuture[0]))
                    .get(context.getPerResultTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(
                    "Metric evaluation timed out for result {} after {}ms",
                    result.getId(),
                    context.getPerResultTimeoutMs(),
                    e);
            tsmdFutures.forEach(f -> f.cancel(true));
        } catch (ExecutionException e) {
            log.warn("Metric evaluation execution error for result {}: {}", result.getId(), e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Metric evaluation interrupted while waiting for result {}", result.getId(), e);
        }

        // Record timeout/missing TSMDs as Failure so the summary reflects incomplete evaluation.
        // Only dispatched metrics are reconciled — skipped/condition-error metrics are intentionally absent.
        for (AggregatedMetricDefinition tsmd : dispatchedTsmds) {
            tsmdResults.putIfAbsent(
                    tsmd.getName(),
                    new TsmdEvaluationResult.Failure(
                            new RuntimeException("Metric evaluation timed out for TSMD " + tsmd.getName()),
                            outputFieldNamesMap.get(tsmd.getName()),
                            clock.millis() - dispatchStartedAtMsByTsmd.get(tsmd.getName())));
        }

        boolean hasError = checkForErrors(tsmdResults);

        ObjectNode metricValues = outputMapper.buildMetricValues(tsmdResults);
        ObjectNode metricInfos = outputMapper.buildMetricInfos(tsmdResults);
        long metricEvalDurationMs = computeMetricEvalDurationMs(tsmdResults);

        return buildItem(
                result,
                context,
                hasError ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS,
                metricValues,
                metricInfos,
                metricEvalDurationMs);
    }

    long computeMetricEvalDurationMs(Map<String, TsmdEvaluationResult> tsmdResults) {
        return tsmdResults.values().stream()
                .mapToLong(r -> switch (r) {
                    case TsmdEvaluationResult.Success success -> success.durationMs();
                    case TsmdEvaluationResult.Failure failure -> failure.durationMs();
                    case TsmdEvaluationResult.ConditionError ignored -> -1L;
                })
                .filter(durationMs -> durationMs >= 0)
                .sum();
    }

    private boolean checkForErrors(Map<String, TsmdEvaluationResult> tsmdResults) {
        for (TsmdEvaluationResult value : tsmdResults.values()) {
            if (value instanceof TsmdEvaluationResult.Failure) {
                return true;
            }
            if (value instanceof TsmdEvaluationResult.Success success
                    && success.response().getOutput() != null) {
                boolean hasMetricError =
                        success.response().getOutput().values().stream().anyMatch(o -> "error".equals(o.getType()));
                if (hasMetricError) {
                    return true;
                }
            }
        }
        return false;
    }

    private EvalSummaryBatchWriteItemDto buildPropagatedItem(
            TestCaseRunResult result, MetricEvaluationContext context) {
        ObjectNode emptyValues = objectMapper.createObjectNode();
        return buildItem(result, context, result.getExecutionStatus(), emptyValues, null, 0L);
    }

    private EvalSummaryBatchWriteItemDto buildItem(
            TestCaseRunResult result,
            MetricEvaluationContext context,
            ExecutionStatus executionStatus,
            ObjectNode metricValues,
            ObjectNode metricInfos,
            long metricEvalDurationMs) {
        return EvalSummaryBatchWriteItemDto.builder()
                .id(UUID.randomUUID())
                .testCaseRunResultId(result.getId())
                .testCaseId(result.getTestCaseId())
                .testCaseName(result.getTestCaseName())
                .runIndex(result.getRunIndex())
                .requestIndex(result.getRequestIndex())
                .totalRequests(result.getTotalRequests())
                .turnIndex(result.getTurnIndex())
                .totalTurns(result.getTotalTurns())
                .testCaseData(parseJsonNode(result.getTestCaseData()))
                .extractedColumns(parseJsonNode(result.getExtractedColumns()))
                .executionStatus(executionStatus)
                .execDurationMs(result.getExecDurationMs())
                .metricEvalDurationMs(metricEvalDurationMs)
                .responseStatusCode(result.getResponseStatusCode())
                .metricValues(metricValues)
                .metricInfos(metricInfos)
                .extractionWarnings(parseJsonNode(result.getExtractionWarnings()))
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

    private void flushIfNeeded(
            List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context, List<String> metricFieldNames) {
        if (buffer.size() >= context.getBatchSize()) {
            doFlush(buffer, context, metricFieldNames);
        }
    }

    private void flushRemaining(
            List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context, List<String> metricFieldNames) {
        if (!buffer.isEmpty()) {
            doFlush(buffer, context, metricFieldNames);
        }
    }

    private void doFlush(
            List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context, List<String> metricFieldNames) {
        try {
            evalSummaryBatchWriteClient.batchWrite(
                    context.getTestSuiteId(),
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    context.getComputedAtMs(),
                    new ArrayList<>(buffer));
            log.debug("Flushed {} eval summaries for run {}", buffer.size(), context.getTestSuiteRunId());

            writeRowScores(buffer, context, metricFieldNames);

            buffer.clear();
        } catch (RuntimeException e) {
            log.error(
                    "Batch write failed for run {}, setting cancellation signal: {}",
                    context.getTestSuiteRunId(),
                    e.getMessage(),
                    e);
            context.getCancellationSignal().set(true);
            buffer.clear();
        }
    }

    /**
     * Computes and writes per-row {@code score}/{@code passed} for this flush's batch, right after its
     * {@code test_case_eval_summaries} rows are committed — one extra SQL query per batch (not per row),
     * reusing {@link EvalSummaryRowScoreComputer}. Skipped entirely when the suite has no {@code overallScore}
     * definition. A batch-write failure here is logged but does not cancel the run: score/passed are
     * regenerable derived data, unlike the eval summaries themselves.
     */
    private void writeRowScores(
            List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context, List<String> metricFieldNames) {
        if (context.getOverallScoreDefinition() == null) {
            return;
        }
        try {
            List<UUID> rowIds =
                    buffer.stream().map(EvalSummaryBatchWriteItemDto::getId).toList();
            Map<UUID, Double> scoresById = evalSummaryRowScoreComputer.computeBatch(
                    context.getOverallScoreDefinition(),
                    metricFieldNames,
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    rowIds);
            if (scoresById.isEmpty()) {
                return;
            }
            List<TestCaseEvalScoreBatchWriteItemDto> items = buffer.stream()
                    .filter(item -> scoresById.containsKey(item.getId()))
                    .map(item -> toScoreItem(item, scoresById.get(item.getId()), context))
                    .toList();
            testCaseEvalScoreService.batchCreate(context.getComputedAtMs(), items);
            log.debug("Wrote {} eval summary scores for run {}", items.size(), context.getTestSuiteRunId());
        } catch (RuntimeException e) {
            log.warn(
                    "Per-row score computation/write failed for run {}, computation {}: {}",
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    e.getMessage(),
                    e);
        }
    }

    private TestCaseEvalScoreBatchWriteItemDto toScoreItem(
            EvalSummaryBatchWriteItemDto item, Double score, MetricEvaluationContext context) {
        Boolean passed = (score != null && context.getOverallScoreThreshold() != null)
                ? score >= context.getOverallScoreThreshold()
                : null;
        return TestCaseEvalScoreBatchWriteItemDto.builder()
                .evalSummaryId(item.getId())
                .score(score)
                .passed(passed)
                .build();
    }
}
