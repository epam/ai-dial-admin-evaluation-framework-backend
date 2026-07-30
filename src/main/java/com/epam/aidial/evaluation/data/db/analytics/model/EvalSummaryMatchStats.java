package com.epam.aidial.evaluation.data.db.analytics.model;

import java.math.BigDecimal;

/**
 * One run's side of a two-run eval-summary comparison, as returned by a single anti-join query.
 *
 * <p>All four values describe the run named in the request, never the run it was compared against:
 * {@code totalRows} is the run's whole population for the resolved computation, {@code matchedRows} how
 * many of those rows have a counterpart key in the other run, {@code matchedSuccessRows} how many matched
 * rows carry a SUCCESS execution status, and {@code avgExecDurationMs} the mean execution duration of the
 * matched rows.
 *
 * <p>{@code matchedSuccessRows} exists to render a per-run success ratio over the compared population
 * ("28 of 29" beside the other run's "27 of 29"), so its denominator is {@code matchedRows}. It is
 * <strong>not</strong> a statistic's sample size, nor even an upper bound on one: a row's stored status is
 * SUCCESS only when the test case executed <em>and</em> every metric evaluated cleanly, while the statistics
 * ignore status entirely and a non-SUCCESS row still contributes its healthy metrics' values. One errored
 * metric can therefore push this count below a metric's actual denominator.
 *
 * <p>The two runs' {@code matchedRows} values may legitimately differ: a run holding several rows for one
 * match key matches all of them, so it can match more rows than its counterpart. {@code matchedRows} plus
 * the size of the run's unmatched-id list always equals {@code totalRows}.
 *
 * <p>{@code avgExecDurationMs} is null when no row matched — SQL {@code avg} over an empty set is null, and
 * the underlying column is {@code NOT NULL}, so null can mean nothing else. It covers <em>all</em> matched
 * rows regardless of execution status, so its denominator is exactly {@code matchedRows}.
 */
public record EvalSummaryMatchStats(
        long totalRows, long matchedRows, long matchedSuccessRows, BigDecimal avgExecDurationMs) {}
