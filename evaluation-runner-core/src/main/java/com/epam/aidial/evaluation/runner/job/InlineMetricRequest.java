package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.Map;

/**
 * Input to {@link InlineMetricEvaluator#evaluate}: the just-built row (SUCCESS, CONTINUE branch of {@link
 * TurnLoopExecutor}) to evaluate metrics against, and the {@code $_metrics} frame entries accumulated so far
 * by earlier turns/requests in this test-case run (see the {@code inline-metric-evaluation} change's {@code
 * design.md} Decision 2/3).
 */
public record InlineMetricRequest(TestCaseRunResult row, Map<String, Object> accumulatedMetrics) {}
