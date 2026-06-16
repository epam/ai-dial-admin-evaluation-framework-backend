package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
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
}
