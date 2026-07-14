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

    /** Returns a page of runnable test cases in deterministic snapshot order ({@code created_at_ms asc, id asc}). */
    List<TestCase> loadRunnablePage(
            UUID datasetId, String filterJson, Collection<UUID> excludedIds, int offset, int limit);

    /**
     * Validates that {@code filterJson} translates against the dataset's test-case schema. Throws
     * {@code ValidationException} (→ HTTP 400) on an unknown field, type error, or malformed filter.
     * A {@code null}/blank filter is a no-op (valid).
     */
    void validateFilter(UUID datasetId, String filterJson);

    /**
     * Counts runnable execution UNITS: runnable single-turn test cases plus conversations whose turns all
     * match the filter. This is the coarse run-creation guard count; per-conversation contiguity/validity is
     * resolved at snapshot (a broken conversation still counts as a unit but yields an ERROR row).
     */
    long countRunnableUnits(UUID datasetId, String filterJson, Collection<UUID> excludedIds);

    /** Page of runnable SINGLE-TURN test cases (deterministic {@code created_at_ms asc, id asc} order). */
    List<TestCase> loadRunnableSingleTurnPage(
            UUID datasetId, String filterJson, Collection<UUID> excludedIds, int offset, int limit);

    /**
     * Page of distinct conversation ids whose turns all match the filter, in deterministic order
     * ({@code min(created_at_ms) asc, conversation_id asc}).
     */
    List<String> loadFilterMatchingConversationIdsPage(UUID datasetId, String filterJson, int offset, int limit);

    /** All turns (any validity, no exclusion applied) of the given conversations, ordered by (conversation_id, turn_index). */
    List<TestCase> loadConversationTurns(UUID datasetId, Collection<String> conversationIds);

    /**
     * Whether the dataset contains ANY conversation row (any row with a non-null {@code conversation_id}).
     * Used by the run-creation guard to reject MCP suites bound to a dataset carrying multi-turn rows.
     */
    boolean datasetHasConversationRows(UUID datasetId);
}
