package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.PassCountTotals;
import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-run pass counts. The writer-side methods ({@link #countForComputation}, {@link #insertIfAbsent},
 * {@link #findRecordedComputations}) are not tombstone-filtered; the client-serving methods
 * ({@link #findLatestRanked}, {@link #findLatestPage}) exclude deleted runs. Both reductions use each run's latest
 * computation, a null computation ranking below any other.
 */
public interface RunPassCountRepository {

    /**
     * Counts the distinct test cases of the computation and how many of them passed (summaries all SUCCESS, no
     * failing score row). Returns 0 of 0 when the computation has neither summaries nor scores.
     */
    PassCountTotals countForComputation(UUID runId, UUID computationId);

    /**
     * Greatest {@code computed_at_ms} among the computation's eval summaries, falling back to its score rows when
     * it has no summaries; empty when it has neither. Writer-side, not tombstone-filtered.
     */
    Optional<Long> findComputedAtMs(UUID runId, UUID computationId);

    /** Inserts the row unless one exists for the run and computation; returns whether it was inserted. */
    boolean insertIfAbsent(RunPassCount row);

    /** The (run, computation) pairs among the given runs that already have a row. */
    List<RecordedComputation> findRecordedComputations(Collection<UUID> runIds);

    /**
     * For each suite, up to {@code perSuiteLimit} latest runs (newest first by run creation, then run id
     * descending), each with its latest computation's counts. Suites without rows are absent from the map; a limit
     * of zero or less returns an empty map.
     */
    Map<UUID, List<RunPassCount>> findLatestRanked(Collection<UUID> suiteIds, int perSuiteLimit);

    /**
     * One row per suite (its latest run), sorted by pass rate ascending with undefined rates last, then newest
     * run first, then suite id. A null {@code suiteIds} means all suites; an empty collection means none.
     */
    Page<RunPassCount> findLatestPage(Collection<UUID> suiteIds, PageRequest pageRequest);
}
