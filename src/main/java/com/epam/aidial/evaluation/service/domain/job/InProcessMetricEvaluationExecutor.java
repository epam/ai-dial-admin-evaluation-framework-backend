package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-process metric evaluation executor using virtual threads bounded by provider semaphores.
 * Iterates results sequentially via cursor pagination, delegates per-row evaluation (and, for
 * non-SUCCESS rows, status propagation) to {@link MetricRowEvaluator}, and buffers EvalSummary records
 * for batch writing.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class InProcessMetricEvaluationExecutor implements MetricEvaluationExecutor {

    private static final int RESULT_PAGE_SIZE = 100;

    private final TestCaseRunResultRepository resultRepository;
    private final MetricRowEvaluator metricRowEvaluator;
    private final EvalSummaryBatchWriteClient evalSummaryBatchWriteClient;

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

        Map<String, Semaphore> providerSemaphores = metricRowEvaluator.buildProviderSemaphores(context);
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

                    if (context.isInlineMode() && result.getExecutionStatus() == ExecutionStatus.SUCCESS) {
                        // Already scored inline during Phase 1 (InlineMetricEvaluatorImpl) — Phase 2
                        // is propagate-only for an inline run, so SUCCESS rows are skipped entirely
                        // (zero provider calls), leaving only non-SUCCESS rows below.
                        continue;
                    }

                    log.debug(
                            "Run {}: evaluating metrics for result {} (testCaseId={}, status={})",
                            context.getTestSuiteRunId(),
                            result.getId(),
                            result.getTestCaseId(),
                            result.getExecutionStatus());

                    MetricRowEvaluationResult rowResult = result.getExecutionStatus() != ExecutionStatus.SUCCESS
                            ? metricRowEvaluator.buildPropagatedItem(result, context)
                            : metricRowEvaluator.evaluateAndBuild(
                                    result, context, providerSemaphores, executor, Map.of());
                    buffer.add(rowResult.item());
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
