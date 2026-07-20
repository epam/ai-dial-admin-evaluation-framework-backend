package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Selects the runnable test cases of a suite — those that are valid, not excluded by the suite's
 * {@code disabledTestCaseIds}, and (when set) matching the suite's {@code testCaseFilter} (a
 * Structured Query DSL filter subtree). This is a stable {@code service}-layer interface implemented
 * in the experimental query layer (interface inversion, mirroring {@code MetricScoreComputation}), so
 * the run pipeline can apply a DSL-authored filter without a compile-time dependency on
 * {@code experimental.query}. Signatures use only primitives and {@link TestCase} so no experimental
 * type leaks upward.
 *
 * <p>{@code filterJson} is the suite's stored filter JSON; {@code null}/blank means "no filter" and
 * selection falls back to validity + exclusion only.
 */
public interface RunnableTestCaseSelector {

    /** Counts the runnable test cases (validity + exclusion + optional filter). */
    long countRunnable(UUID datasetId, String filterJson, Collection<UUID> excludedIds);

    /**
     * Validates that {@code filterJson} translates against the dataset's test-case schema. Throws
     * {@code ValidationException} (→ HTTP 400) on an unknown field, type error, or malformed filter.
     * A {@code null}/blank filter is a no-op (valid).
     */
    void validateFilter(UUID datasetId, String filterJson);

    /** Page of runnable SINGLE-TURN test cases (deterministic {@code created_at_ms asc, id asc} order). */
    List<TestCase> loadRunnableSingleTurnPage(
            UUID datasetId, String filterJson, Collection<UUID> excludedIds, int offset, int limit);

    /**
     * Page of distinct multiTurn ids that have at least one turn matching the filter (row-level, like
     * disable), in deterministic order ({@code min(created_at_ms) asc, multi_turn_id asc}).
     */
    List<String> loadRunnableMultiTurnIdsPage(UUID datasetId, String filterJson, int offset, int limit);

    /**
     * The filter-matching turns of the given multiTurns, ordered by (multi_turn_id, turn_index).
     * Only the filter is applied here; validity and exclusion are resolved at assembly time.
     */
    List<TestCase> loadMultiTurnTurns(UUID datasetId, Collection<String> multiTurnIds, String filterJson);

    /**
     * Whether the dataset contains ANY multiTurn row (any row with a non-null {@code multi_turn_id}).
     * Used by the run-creation guard to reject MCP suites bound to a dataset carrying multi-turn rows.
     */
    boolean datasetHasMultiTurnRows(UUID datasetId);
}
