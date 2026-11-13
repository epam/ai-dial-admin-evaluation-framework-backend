package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/**
 * Per-run tally of {@code test_case_eval_scores} rows for one computation, split into the four buckets the
 * pass-rate rule needs: {@code passed = true}, {@code passed = false}, SUCCESS rows with no {@code passed}
 * value (unscored, or no threshold), and rows whose {@code execution_status} is not SUCCESS.
 */
public record RunPassCounts(UUID runId, long passedTrue, long passedFalse, long unscored, long failed) {

    /** Every row of the run's computation. */
    public long total() {
        return passedTrue + passedFalse + unscored + failed;
    }
}
