package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/**
 * One run's latest-computation pass-rate breakdown, as returned by
 * {@link com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository#countPassRateByLatestComputation(java.util.List)}.
 *
 * <p>The five bucket counts partition {@code total} by construction: {@code failed} is every row whose
 * {@code execution_status} is not {@code SUCCESS}; {@code successPassed} / {@code successNotPassed} are
 * {@code SUCCESS} rows with a {@code test_case_eval_scores.passed} value of {@code true} / {@code false};
 * {@code successNoVerdict} is every remaining {@code SUCCESS} row — no score row yet, or a present row with
 * {@code passed = NULL}. No {@code RecordMapper}: a pure carrier, mapped inline like
 * {@link EvalSummaryMatchStats}.
 */
public record RunPassRateStats(
        UUID runId,
        UUID computationId,
        long failed,
        long successPassed,
        long successNotPassed,
        long successNoVerdict,
        long total) {}
