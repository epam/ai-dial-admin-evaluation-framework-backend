package com.epam.aidial.evaluation.runner.job;

import java.util.Map;

/**
 * Output of {@link InlineMetricEvaluator#evaluate}: the {@code $_metrics} frame entry produced for this row
 * (folded into the caller's {@code accumulatedMetrics}) and whether metric evaluation failed for this row
 * (see the {@code inline-metric-evaluation} change's {@code design.md} Decision 3/6 — a failure aborts the
 * chain, but never replaces the row itself or flips its {@code executionStatus}).
 */
public record InlineMetricResult(Map<String, Object> frameEntry, boolean failed) {}
