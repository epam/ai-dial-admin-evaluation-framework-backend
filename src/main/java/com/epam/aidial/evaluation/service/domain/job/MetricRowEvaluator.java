package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.ConditionContext;
import com.epam.aidial.evaluation.service.domain.ConditionDecision;
import com.epam.aidial.evaluation.service.domain.ConditionExpressionEvaluator;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Per-row metric evaluation: dispatches a single {@link TestCaseRunResult}'s TSMDs to the configured
 * metric providers (respecting each TSMD's {@code condition} and the configured per-result timeout), or
 * — for a non-SUCCESS row — propagates the row's own status untouched. Builds the resulting
 * {@link EvalSummaryBatchWriteItemDto} either way.
 *
 * <p>Extracted from {@link InProcessMetricEvaluationExecutor} so the same per-row logic can be shared by
 * Phase 2's batch executor and inline (Phase 1) evaluation: both need the same dispatch/condition/timeout
 * handling, but only the inline caller needs the raw {@link TsmdEvaluationResult} map (via
 * {@link MetricRowEvaluationResult#tsmdResults()}) to build a {@code $_metrics} frame entry.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricRowEvaluator {

    private final MetricEvaluationWorker worker;
    private final MetricOutputMapper outputMapper;
    private final ObjectMapper objectMapper;
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;
    private final ConditionExpressionEvaluator conditionExpressionEvaluator;
    private final Clock clock;

    public Map<String, Semaphore> buildProviderSemaphores(MetricEvaluationContext context) {
        Map<String, Semaphore> semaphores = new HashMap<>();
        for (AggregatedMetricDefinition tsmd : context.getAggregatedTsmds()) {
            semaphores.computeIfAbsent(
                    tsmd.getDeclarationProviderId(), k -> new Semaphore(context.getDefaultConcurrencyPerProvider()));
        }
        return semaphores;
    }

    public MetricRowEvaluationResult evaluateAndBuild(
            TestCaseRunResult result,
            MetricEvaluationContext context,
            Map<String, Semaphore> providerSemaphores,
            ExecutorService executor,
            Map<String, Object> accumulatedMetrics) {
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
                            EvaluationResponseDto response =
                                    worker.evaluate(tsmd, result, semaphore, context, accumulatedMetrics);
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

        EvalSummaryBatchWriteItemDto item = buildItem(
                result,
                context,
                hasError ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS,
                metricValues,
                metricInfos,
                metricEvalDurationMs);

        return new MetricRowEvaluationResult(item, tsmdResults, hasError);
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

    public MetricRowEvaluationResult buildPropagatedItem(TestCaseRunResult result, MetricEvaluationContext context) {
        ObjectNode emptyValues = objectMapper.createObjectNode();
        EvalSummaryBatchWriteItemDto item =
                buildItem(result, context, result.getExecutionStatus(), emptyValues, null, 0L);
        return new MetricRowEvaluationResult(item, Map.of(), false);
    }

    /**
     * Builds a wholesale-FAILED {@link EvalSummaryBatchWriteItemDto} for a row whose inline evaluation
     * itself threw (as opposed to a dispatched TSMD returning an error — see
     * {@link TsmdEvaluationResult.Failure}). Used by {@code InlineMetricEvaluatorImpl}'s total-seam catch
     * block so the row still gets exactly one eval summary even when {@link #evaluateAndBuild} never got
     * far enough to build one itself; the row's own {@code executionStatus} is untouched (design.md
     * Decision 6 — a metric-side failure never flips the row away from SUCCESS), but its EvalSummary is
     * marked FAILED with the thrown exception's message recorded as a wholesale metric info.
     */
    public MetricRowEvaluationResult buildFailedItem(
            TestCaseRunResult result, MetricEvaluationContext context, String errorMessage) {
        ObjectNode emptyValues = objectMapper.createObjectNode();
        ObjectNode metricInfos = objectMapper.createObjectNode();
        metricInfos.put("error", errorMessage);
        EvalSummaryBatchWriteItemDto item =
                buildItem(result, context, ExecutionStatus.FAILED, emptyValues, metricInfos, 0L);
        return new MetricRowEvaluationResult(item, Map.of(), true);
    }

    private EvalSummaryBatchWriteItemDto buildItem(
            TestCaseRunResult result,
            MetricEvaluationContext context,
            ExecutionStatus executionStatus,
            ObjectNode metricValues,
            ObjectNode metricInfos,
            long metricEvalDurationMs) {
        return EvalSummaryBatchWriteItemDto.builder()
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
}
