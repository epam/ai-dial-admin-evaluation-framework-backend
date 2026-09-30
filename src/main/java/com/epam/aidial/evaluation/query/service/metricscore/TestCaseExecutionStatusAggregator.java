package com.epam.aidial.evaluation.query.service.metricscore;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Aggregates a test case's {@code test_case_eval_summaries} rows for a computation — every {@code
 * run_index}/{@code request_index}/{@code turn_index} combination — into a single per-test-case {@link
 * ExecutionStatus}, collapsed to {@code SUCCESS}/{@code FAILED} only: {@code FAILED} if any row's {@code
 * execution_status <> 'SUCCESS'}, else {@code SUCCESS}.
 *
 * <p>Deliberately separate from {@link TestCaseMetricScoreAggregator}/{@code
 * test_case_metric_scores_aggregated}: that aggregator's result set is driven by {@code
 * jsonb_each(metric_values)}, so a row whose {@code metric_values = '{}'} (every metric condition-skipped, or
 * the row failed before any metric ran) never appears in it at all. This aggregator scans {@code
 * test_case_eval_summaries} directly, so it sees every row regardless of whether it produced any numeric
 * metric value — which is exactly what "did one of this test case's turns/requests/iterations fail" needs.
 *
 * <p>A metric execution failure already flips its row's own {@code execution_status} to {@code FAILED}
 * ({@code InProcessMetricEvaluationExecutor.checkForErrors}), so this single OR-across-rows check correctly
 * folds metric failures and upstream execution failures (TIMEOUT/ERROR/FAILED) into one signal, while a
 * condition-skipped metric — which never changes a row's {@code execution_status} — has no effect on it.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseExecutionStatusAggregator {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    /**
     * Re-aggregates the <strong>entire</strong> row set (not just the current flush batch) of every id in
     * {@code testCaseIds}, for the given run/computation — required for correctness since one test case's
     * rows can straddle multiple flush batches.
     *
     * @return one entry per id in {@code testCaseIds} that has at least one row for the run/computation;
     *     every id passed in is expected to have at least one row, since it is derived from a buffered
     *     write item that already belongs to this run/computation
     */
    public Map<UUID, ExecutionStatus> aggregate(UUID runId, UUID computationId, List<UUID> testCaseIds) {
        if (testCaseIds.isEmpty()) {
            return Map.of();
        }
        List<String> testCaseIdStrings =
                testCaseIds.stream().map(UUID::toString).toList();

        Field<Boolean> hasFailure =
                DSL.boolOr(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS.ne(ExecutionStatus.SUCCESS.name()));

        return dsl
                .select(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID, hasFailure)
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID.eq(computationId.toString()))
                .and(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID.in(testCaseIdStrings))
                .groupBy(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID)
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        r -> UUID.fromString(r.value1()),
                        r -> Boolean.TRUE.equals(r.value2()) ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS));
    }
}
