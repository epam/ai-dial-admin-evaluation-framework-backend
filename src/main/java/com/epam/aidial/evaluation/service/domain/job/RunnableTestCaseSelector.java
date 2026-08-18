package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import java.util.List;
import java.util.UUID;

/**
 * Selects the runnable test cases of a suite — those that are valid and (when set) matching the
 * suite's {@code testCaseFilter} (a Structured Query DSL filter subtree). This is a stable
 * {@code service}-layer interface implemented in the experimental query layer (interface inversion,
 * mirroring {@code MetricScoreComputation}), so the run pipeline can apply a DSL-authored filter
 * without a compile-time dependency on {@code experimental.query}. Signatures use only primitives and
 * {@link TestCase} so no experimental type leaks upward.
 *
 * <p>{@code filterJson} is the suite's stored filter JSON; {@code null}/blank means "no filter" and
 * selection falls back to validity only.
 */
public interface RunnableTestCaseSelector {

    /** Counts the runnable test cases (validity + optional filter). */
    long countRunnable(UUID datasetId, String filterJson);

    /** Returns a page of runnable test cases in deterministic snapshot order ({@code created_at_ms asc, id asc}). */
    List<TestCase> loadRunnablePage(UUID datasetId, String filterJson, int offset, int limit);

    /**
     * Validates that {@code filterJson} translates against the dataset's test-case schema. Throws
     * {@code ValidationException} (→ HTTP 400) on an unknown field, type error, or malformed filter.
     * A {@code null}/blank filter is a no-op (valid).
     */
    void validateFilter(UUID datasetId, String filterJson);
}
