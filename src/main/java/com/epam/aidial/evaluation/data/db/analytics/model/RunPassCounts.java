package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/**
 * Per-run tally of the test cases of one computation, split into the four buckets the pass-rate rule needs:
 * {@code passed = true}, {@code passed = false}, SUCCESS cases with no {@code passed} value (unscored, or no
 * threshold — including SUCCESS cases the writer left without a {@code test_case_eval_scores} row), and cases
 * whose {@code execution_status} is not SUCCESS (never unscored).
 */
public record RunPassCounts(UUID runId, long passedTrue, long passedFalse, long unscored, long failed) {

    /** Every row of the run's computation. */
    public long total() {
        return passedTrue + passedFalse + unscored + failed;
    }
}
