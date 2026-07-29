package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryMatchStats;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricAggregationResult;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricPath;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvalSummaryRepository {

    void saveAll(List<EvalSummary> summaries);

    CursorPage<EvalSummary> findAll(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size);

    /**
     * Cursor-paginated read for the CSV export path WITHOUT request/response body JOIN. Returns
     * the full set of own-table columns (including {@code metric_infos} and
     * {@code extraction_warnings}) but skips the {@code test_case_run_results} JOIN. Use when
     * the effective export column set contains no body descriptors.
     */
    CursorPage<EvalSummary> findAllForExport(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size);

    /**
     * Cursor-paginated read for the CSV export path WITH request/response body JOIN. Lifts the
     * LEFT JOIN to {@code test_case_run_results} from the single-row detail query into a list
     * query so {@code requestBody} / {@code responseBody} are populated per row. Use when the
     * effective export column set contains at least one body descriptor.
     */
    CursorPage<EvalSummary> findAllForExportWithBodies(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size);

    Optional<EvalSummary> findById(UUID id);

    long count(List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs);

    List<MetricAggregationResult> aggregate(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, List<MetricPath> metrics);

    /**
     * Counts one run's rows against another run's population, matching on
     * {@code lower(test_case_name)} + {@code run_index} + {@code turn_index} and also returning the matched
     * rows' mean {@code exec_duration_ms}.
     *
     * <p>A row matches if and only if its key occurs in {@code otherRunId}'s population, so where this run
     * holds several rows for one key <strong>all</strong> of them match and none is dropped. The other side
     * is reduced to a distinct key set before joining, which makes fan-out impossible — so the counts and
     * the average are plain aggregates over this run's own rows, each contributing exactly once. Reversing
     * the arguments yields the other side's numbers.
     *
     * <p>Materialises no rows, so a caller can enforce a bound on the unmatched-id list
     * ({@code totalRows - matchedRows}) before fetching a single id.
     */
    EvalSummaryMatchStats countMatches(UUID runId, UUID computationId, UUID otherRunId, UUID otherComputationId);

    /**
     * Ids of {@code runId}'s rows that have <strong>no</strong> counterpart key in {@code otherRunId}'s
     * population — the same match rule as {@link #countMatches}, inverted.
     *
     * <p>Deterministically ordered, so identical requests return an identical list. Callers exclude these
     * ids to reproduce the matched population; an empty result therefore means the whole run matched and no
     * exclusion is needed.
     */
    List<UUID> findUnmatchedIds(UUID runId, UUID computationId, UUID otherRunId, UUID otherComputationId);
}
