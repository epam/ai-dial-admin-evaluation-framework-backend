package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    public void execute(MetricEvaluationContext context) {
        if (context.getAggregatedTsmds().isEmpty()) {
            log.info("No TSMDs in context for run {}, skipping metric evaluation", context.getTestSuiteRunId());
            return;
        }

        log.info(
                "Starting metric evaluation for run {}, {} TSMDs",
                context.getTestSuiteRunId(),
                context.getAggregatedTsmds().size());

        writeRunMetricSnapshots(context);

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

                flushIfNeeded(buffer, context);
                cursor = page.nextCursor();
            } while (cursor != null);
        } finally {
            flushRemaining(buffer, context);
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

        List<CompletableFuture<Void>> tsmdFutures = new ArrayList<>();
        for (AggregatedMetricDefinition tsmd : context.getAggregatedTsmds()) {
            List<String> fieldNames = outputFieldNamesMap.get(tsmd.getName());
            Semaphore semaphore = providerSemaphores.get(tsmd.getDeclarationProviderId());
            log.debug(
                    "Run {}: dispatching metric '{}' for result {}",
                    context.getTestSuiteRunId(),
                    tsmd.getName(),
                    result.getId());
            CompletableFuture<Void> tsmdFuture = CompletableFuture.runAsync(
                    () -> {
                        try {
                            tsmdResults.put(
                                    tsmd.getName(),
                                    new TsmdEvaluationResult.Success(
                                            worker.evaluate(tsmd, result, semaphore, context), fieldNames));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn(
                                    "Metric evaluation interrupted for TSMD {} on result {}",
                                    tsmd.getName(),
                                    result.getId(),
                                    e);
                            tsmdResults.put(tsmd.getName(), new TsmdEvaluationResult.Failure(e, fieldNames));
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Metric evaluation failed for TSMD {} on result {}: {}",
                                    tsmd.getName(),
                                    result.getId(),
                                    e.getMessage(),
                                    e);
                            tsmdResults.put(tsmd.getName(), new TsmdEvaluationResult.Failure(e, fieldNames));
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

        // Record timeout/missing TSMDs as Failure so the summary reflects incomplete evaluation
        for (AggregatedMetricDefinition tsmd : context.getAggregatedTsmds()) {
            tsmdResults.putIfAbsent(
                    tsmd.getName(),
                    new TsmdEvaluationResult.Failure(
                            new RuntimeException("Metric evaluation timed out for TSMD " + tsmd.getName()),
                            outputFieldNamesMap.get(tsmd.getName())));
        }

        boolean hasError = checkForErrors(tsmdResults);

        ObjectNode metricValues = outputMapper.buildMetricValues(tsmdResults);
        ObjectNode metricInfos = outputMapper.buildMetricInfos(tsmdResults);

        return buildItem(
                result,
                context,
                hasError ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS,
                metricValues,
                metricInfos);
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
        return buildItem(result, context, result.getExecutionStatus(), emptyValues, null);
    }

    private EvalSummaryBatchWriteItemDto buildItem(
            TestCaseRunResult result,
            MetricEvaluationContext context,
            ExecutionStatus executionStatus,
            ObjectNode metricValues,
            ObjectNode metricInfos) {
        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(result.getId())
                .testCaseId(result.getTestCaseId())
                .testCaseName(result.getTestCaseName())
                .runIndex(result.getRunIndex())
                .testCaseData(parseJsonNode(result.getTestCaseData()))
                // extractedColumns is stored verbatim: single-step results carry scalar values, multi-step
                // results carry per-column arrays. No normalization — turn selection is a per-binding concern.
                .extractedColumns(parseJsonNode(result.getExtractedColumns()))
                .executionStatus(executionStatus)
                .execDurationMs(result.getExecDurationMs())
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

    private void flushIfNeeded(List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context) {
        if (buffer.size() >= context.getBatchSize()) {
            doFlush(buffer, context);
        }
    }

    private void flushRemaining(List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context) {
        if (!buffer.isEmpty()) {
            doFlush(buffer, context);
        }
    }

    private void doFlush(List<EvalSummaryBatchWriteItemDto> buffer, MetricEvaluationContext context) {
        try {
            evalSummaryBatchWriteClient.batchWrite(
                    context.getTestSuiteId(),
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    context.getComputedAtMs(),
                    new ArrayList<>(buffer));
            log.debug("Flushed {} eval summaries for run {}", buffer.size(), context.getTestSuiteRunId());
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
}
