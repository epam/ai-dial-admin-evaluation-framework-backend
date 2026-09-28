package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.query.service.metricscore.EvalSummaryRowScoreComputer;
import com.epam.aidial.evaluation.query.service.metricscore.MetricField;
import com.epam.aidial.evaluation.query.service.metricscore.MetricFieldDiscoverer;
import com.epam.aidial.evaluation.query.service.metricscore.TestCaseExecutionStatusAggregator;
import com.epam.aidial.evaluation.query.service.metricscore.TestCaseMetricScoreAggregator;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.ConditionContext;
import com.epam.aidial.evaluation.service.domain.ConditionDecision;
import com.epam.aidial.evaluation.service.domain.ConditionExpressionEvaluator;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.analytics.TestCaseEvalScoreService;
import com.epam.aidial.evaluation.service.domain.analytics.TestCaseMetricScoreAggregatedService;
import com.epam.aidial.evaluation.service.domain.dto.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseEvalScoreBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseMetricScoreAggregatedBatchWriteItemDto;
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
    private final TestCaseExecutionStatusAggregator testCaseExecutionStatusAggregator;
    private final TestCaseMetricScoreAggregator testCaseMetricScoreAggregator;
    private final TestCaseMetricScoreAggregatedService testCaseMetricScoreAggregatedService;
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
        List<MetricField> metricFields = discoverMetricFields(context);

        Map<String, Semaphore> providerSemaphores = buildProviderSemaphores(context);
        List<FilterCondition> filters = buildRunIdFilters(context);
        List<EvalSummaryBatchWriteItemDto> buffer = new ArrayList<>();
        Cursor cursor = null;

        ExecutorService executor = context.getExecutor();
        try {
            do {
                CursorPage<TestCaseRunResult> page =
                        resultRepository.findAll(filters, context.getRunCreatedAtMs(), cursor, RESULT_PAGE_SIZE);

                for (TestCaseRunResult result : page.content()) {
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

                flushIfNeeded(buffer, context, metricFields);
                cursor = page.nextCursor();
            } while (cursor != null);
        } finally {
            flushRemaining(buffer, context, metricFields);
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
     * The run's discovered numeric metric fields, read back once via the same {@link MetricFieldDiscoverer}
     * Phase 3 uses — so a {@code Mean} overall score's divisor can never disagree between the two phases for
     * the same run, and so {@link #writeAggregatedMetricScores} reuses the exact same field discovery
     * {@link #writeRowScores} does (via {@link MetricField#flattenedName()}), rather than re-deriving it. One
     * query per {@link #execute} call, not per flush.
     */
    private List<MetricField> discoverMetricFields(MetricEvaluationContext context) {
        List<RunMetricSnapshot> snapshots = runMetricSnapshotRepository.findByRunIdAndComputationId(
                context.getTestSuiteRunId(), context.getComputationId());
        return metricFieldDiscoverer.discover(snapshots);
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
            List<EvalSummaryBatchWriteItemDto> buffer,
            MetricEvaluationContext context,
            List<MetricField> metricFields) {
        if (buffer.size() >= context.getBatchSize()) {
            doFlush(buffer, context, metricFields);
        }
    }

    private void flushRemaining(
            List<EvalSummaryBatchWriteItemDto> buffer,
            MetricEvaluationContext context,
            List<MetricField> metricFields) {
        if (!buffer.isEmpty()) {
            doFlush(buffer, context, metricFields);
        }
    }

    private void doFlush(
            List<EvalSummaryBatchWriteItemDto> buffer,
            MetricEvaluationContext context,
            List<MetricField> metricFields) {
        // Drain the buffer before writing so a failed batch is never re-sent by the caller's
        // finally { flushRemaining(...) } — EvalSummaryBatchWriteClient chunks internally and may
        // have already committed some chunks before throwing.
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>(buffer);
        buffer.clear();
        try {
            evalSummaryBatchWriteClient.batchWrite(
                    context.getTestSuiteId(),
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    context.getComputedAtMs(),
                    items);
            log.debug("Flushed {} eval summaries for run {}", items.size(), context.getTestSuiteRunId());

            // Aggregated metric scores MUST be written before row scores: writeRowScores' Mean/
            // WeightedMean/CustomFunction computation reads test_case_metric_scores_aggregated, which
            // writeAggregatedMetricScores is what populates for this batch's test cases.
            writeAggregatedMetricScores(items, context, metricFields);
            writeRowScores(items, context, metricFields);
        } catch (RuntimeException e) {
            log.error("Batch write failed for run {}: {}", context.getTestSuiteRunId(), e.getMessage(), e);
            throw new AnalyticsWriteException(
                    "Failed to write eval summaries for run " + context.getTestSuiteRunId(), e);
        }
    }

    /**
     * Computes and writes exactly one {@code test_case_eval_scores} row per test case in this flush's
     * batch — never one per raw {@code test_case_eval_summaries} row — reusing {@link
     * EvalSummaryRowScoreComputer}, which reads the just-written {@code test_case_metric_scores_aggregated}
     * data (see {@link #writeAggregatedMetricScores}, which MUST run first). Every row written here carries
     * {@code eval_summary_id = NULL}: it is computed from, and corresponds 1:1 to, the one-row-per-test-case
     * aggregated row, so no broadcast/dedup is needed on this path (see {@code
     * PostgresTestCaseEvalScoreRepository}'s partial-unique-index upsert). First computes each test case's
     * aggregated {@code execution_status} (see {@link TestCaseExecutionStatusAggregator}) — OR-ed across
     * <strong>all</strong> of that test case's rows for the computation, not just this batch — and issues the
     * score SQL query only for test cases whose aggregate is {@code SUCCESS}; a {@code FAILED}-aggregate test
     * case gets {@code score = null} without its id ever reaching that query. A {@code FAILED} test case
     * always gets a written row regardless of whether it has any numeric metric samples; a {@code SUCCESS}
     * test case only gets one when the score query actually returned a value for it — preserving {@code
     * test_case_metric_scores_aggregated}'s "absent = no numeric sample" contract for the SUCCESS case (a
     * metric-less/all-condition-skipped SUCCESS test case stays absent from both tables). Skipped entirely
     * when the suite has no {@code overallScore} definition. A batch-write failure here is logged but does
     * not cancel the run: score/passed/execution_status are regenerable derived data, unlike the eval
     * summaries themselves.
     */
    private void writeRowScores(
            List<EvalSummaryBatchWriteItemDto> buffer,
            MetricEvaluationContext context,
            List<MetricField> metricFields) {
        if (context.getOverallScoreDefinition() == null) {
            return;
        }
        try {
            List<UUID> testCaseIds = buffer.stream()
                    .map(EvalSummaryBatchWriteItemDto::getTestCaseId)
                    .distinct()
                    .toList();
            Map<UUID, ExecutionStatus> statusByTestCase = testCaseExecutionStatusAggregator.aggregate(
                    context.getTestSuiteRunId(), context.getComputationId(), testCaseIds);
            if (statusByTestCase.isEmpty()) {
                return;
            }
            List<UUID> successTestCaseIds = statusByTestCase.entrySet().stream()
                    .filter(entry -> entry.getValue() == ExecutionStatus.SUCCESS)
                    .map(Map.Entry::getKey)
                    .toList();
            Map<UUID, Double> scoresByTestCase = successTestCaseIds.isEmpty()
                    ? Map.of()
                    : evalSummaryRowScoreComputer.computeByTestCase(
                            context.getOverallScoreDefinition(),
                            metricFields,
                            context.getTestSuiteRunId(),
                            context.getComputationId(),
                            successTestCaseIds);
            Map<UUID, String> testCaseNamesById = buffer.stream()
                    .collect(Collectors.toMap(
                            EvalSummaryBatchWriteItemDto::getTestCaseId,
                            EvalSummaryBatchWriteItemDto::getTestCaseName,
                            (first, second) -> first));
            List<TestCaseEvalScoreBatchWriteItemDto> items = statusByTestCase.entrySet().stream()
                    // A FAILED test case always gets a row (score=null), even with zero numeric
                    // samples — the point of this aggregation. A SUCCESS test case only gets one when
                    // it actually has a computed score, preserving test_case_metric_scores_aggregated's
                    // existing "absent = no numeric sample" contract: a SUCCESS test case with nothing
                    // numeric (e.g. every metric condition-skipped) stays absent from both tables, same
                    // as before this change.
                    .filter(entry ->
                            entry.getValue() == ExecutionStatus.FAILED || scoresByTestCase.containsKey(entry.getKey()))
                    .map(entry -> toScoreItem(
                            entry.getKey(),
                            testCaseNamesById.get(entry.getKey()),
                            scoresByTestCase.get(entry.getKey()),
                            entry.getValue(),
                            context))
                    .toList();
            testCaseEvalScoreService.batchUpsert(context.getComputedAtMs(), items);
            log.debug("Wrote {} eval summary scores for run {}", items.size(), context.getTestSuiteRunId());
        } catch (RuntimeException e) {
            log.warn(
                    "Per-test-case score computation/write failed for run {}, computation {}: {}",
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Computes and writes the per-test-case {@code metric_scores} aggregation for this flush's batch,
     * reusing {@link TestCaseMetricScoreAggregator}. Runs <strong>before</strong> {@link #writeRowScores},
     * which reads this data back for {@code Mean}/{@code WeightedMean}/{@code CustomFunction}. Re-aggregates
     * each affected test case's <strong>entire</strong> row set for the computation (not just this
     * batch's rows), since a test case's rows can straddle multiple flush batches — the upsert in
     * {@link TestCaseMetricScoreAggregatedService} makes this idempotent. Skipped entirely when the run
     * has no discovered metric fields. A failure here is logged but does not cancel the run:
     * {@code metric_scores} is regenerable derived data, unlike the eval summaries themselves — kept as
     * its own {@code try}/{@code catch}, isolated from {@link #writeRowScores}'s, so a failure in one
     * does not suppress the other's own error visibility.
     */
    private void writeAggregatedMetricScores(
            List<EvalSummaryBatchWriteItemDto> buffer,
            MetricEvaluationContext context,
            List<MetricField> metricFields) {
        if (metricFields.isEmpty()) {
            return;
        }
        try {
            List<UUID> testCaseIds = buffer.stream()
                    .map(EvalSummaryBatchWriteItemDto::getTestCaseId)
                    .distinct()
                    .toList();
            List<TestCaseMetricScoreAggregatedBatchWriteItemDto> items = testCaseMetricScoreAggregator.aggregate(
                    context.getTestSuiteRunId(), context.getComputationId(), testCaseIds);
            testCaseMetricScoreAggregatedService.batchUpsert(context.getComputedAtMs(), items);
            log.debug(
                    "Wrote {} test case metric score aggregates for run {}", items.size(), context.getTestSuiteRunId());
        } catch (RuntimeException e) {
            log.warn(
                    "Aggregated metric score computation/write failed for run {}, computation {}: {}",
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    e.getMessage(),
                    e);
        }
    }

    private TestCaseEvalScoreBatchWriteItemDto toScoreItem(
            UUID testCaseId,
            String testCaseName,
            Double score,
            ExecutionStatus executionStatus,
            MetricEvaluationContext context) {
        Double effectiveScore = executionStatus == ExecutionStatus.FAILED ? null : score;
        Boolean passed = (effectiveScore != null && context.getOverallScoreThreshold() != null)
                ? effectiveScore >= context.getOverallScoreThreshold()
                : null;
        return TestCaseEvalScoreBatchWriteItemDto.builder()
                .testSuiteRunId(context.getTestSuiteRunId())
                .testCaseId(testCaseId)
                .testCaseName(testCaseName)
                .computationId(context.getComputationId())
                .executionStatus(executionStatus)
                .score(effectiveScore)
                .passed(passed)
                .build();
    }
}
