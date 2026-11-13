package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Row2;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseEvalScoreRepository implements TestCaseEvalScoreRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    /** Insert-only: scores are computed once per test case after the last flush, so an existing row is left untouched. */
    @Override
    public void saveAll(List<TestCaseEvalScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        List<Query> queries = scores.stream()
                .map(s -> (Query) dsl.insertInto(TEST_CASE_EVAL_SCORES)
                        .set(TEST_CASE_EVAL_SCORES.ID, s.getId().toString())
                        .set(
                                TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID,
                                s.getTestSuiteRunId().toString())
                        .set(
                                TEST_CASE_EVAL_SCORES.TEST_CASE_ID,
                                s.getTestCaseId().toString())
                        .set(TEST_CASE_EVAL_SCORES.TEST_CASE_NAME, s.getTestCaseName())
                        .set(
                                TEST_CASE_EVAL_SCORES.COMPUTATION_ID,
                                s.getComputationId().toString())
                        .set(
                                TEST_CASE_EVAL_SCORES.EXECUTION_STATUS,
                                s.getExecutionStatus().name())
                        .set(TEST_CASE_EVAL_SCORES.SCORE, s.getScore())
                        .set(TEST_CASE_EVAL_SCORES.PASSED, s.getPassed())
                        .set(TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS, s.getComputedAtMs())
                        .onConflict(
                                TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID,
                                TEST_CASE_EVAL_SCORES.TEST_CASE_ID,
                                TEST_CASE_EVAL_SCORES.COMPUTATION_ID)
                        .doNothing())
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} eval summary scores", scores.size());
    }

    @Override
    public Map<UUID, RunPassCounts> countByRunAndComputation(Map<UUID, UUID> runToComputation) {
        if (runToComputation == null || runToComputation.isEmpty()) {
            return Map.of();
        }
        final List<Row2<String, String>> pairs = runToComputation.entrySet().stream()
                .map(e -> DSL.row(e.getKey().toString(), e.getValue().toString()))
                .toList();
        final String success = ExecutionStatus.SUCCESS.name();
        final Field<Integer> passedTrue = DSL.count().filterWhere(TEST_CASE_EVAL_SCORES.PASSED.isTrue());
        final Field<Integer> passedFalse = DSL.count().filterWhere(TEST_CASE_EVAL_SCORES.PASSED.isFalse());
        final Field<Integer> unscored = DSL.count()
                .filterWhere(TEST_CASE_EVAL_SCORES.PASSED.isNull().and(TEST_CASE_EVAL_SCORES.EXECUTION_STATUS.eq(success)));
        final Field<Integer> failed = DSL.count().filterWhere(TEST_CASE_EVAL_SCORES.EXECUTION_STATUS.ne(success));

        final Map<UUID, RunPassCounts> result = new HashMap<>();
        dsl.select(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID, passedTrue, passedFalse, unscored, failed)
                .from(TEST_CASE_EVAL_SCORES)
                .where(DSL.row(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID, TEST_CASE_EVAL_SCORES.COMPUTATION_ID)
                        .in(pairs))
                .and(RunDeletionFilter.notDeleted(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID))
                .groupBy(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID)
                .forEach(r -> {
                    final UUID runId = UUID.fromString(r.value1());
                    result.put(
                            runId,
                            new RunPassCounts(runId, r.value2(), r.value3(), r.value4(), r.value5()));
                });
        return result;
    }
}
