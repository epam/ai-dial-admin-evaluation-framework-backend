package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import java.util.Map;

/**
 * Result of {@link MetricRowEvaluator} handling a single {@code TestCaseRunResult} row: either
 * dispatching its TSMDs to the configured metric providers, or propagating a non-SUCCESS row's own
 * status untouched.
 *
 * @param item        the {@link EvalSummaryBatchWriteItemDto} ready for the eval-summary batch write
 * @param tsmdResults the raw per-TSMD results (empty for a propagated row) an inline evaluator can fold
 *                    into its {@code $_metrics} frame entry
 * @param hasError    whether any dispatched TSMD failed (transport failure, timeout, or a metric-level
 *                    error output) — {@code false} for a propagated row
 */
public record MetricRowEvaluationResult(
        EvalSummaryBatchWriteItemDto item, Map<String, TsmdEvaluationResult> tsmdResults, boolean hasError) {}
