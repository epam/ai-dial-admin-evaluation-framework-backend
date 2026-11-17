package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

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
import org.jooq.Table;
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

    private static final String RUN_ID = "run_id";
    private static final String COMPUTATION_ID = "computation_id";
    private static final String TEST_CASE_ID = "test_case_id";
    private static final String STATUS = "execution_status";
    private static final String PASSED = "passed";

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

    /**
     * One row per test case: the distinct test cases of the eval summaries (several summary rows per case from
     * repetitions, turns and requests collapse to one) full-joined with the score rows. The metric writer leaves
     * out SUCCESS cases with no numeric sample, so a case without a score row is an unscored SUCCESS case.
     */
    @Override
    public Map<UUID, RunPassCounts> countByRunAndComputation(Map<UUID, UUID> runToComputation) {
        if (runToComputation == null || runToComputation.isEmpty()) {
            return Map.of();
        }
        final List<Row2<String, String>> pairs = runToComputation.entrySet().stream()
                .map(e -> DSL.row(e.getKey().toString(), e.getValue().toString()))
                .toList();

        final Table<?> cases = dsl.selectDistinct(
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.as(RUN_ID),
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID.as(COMPUTATION_ID),
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID.as(TEST_CASE_ID))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(DSL.row(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID, TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID)
                        .in(pairs))
                .and(RunDeletionFilter.notDeleted(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID))
                .asTable("cases");
        final Table<?> scores = dsl.select(
                        TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID.as(RUN_ID),
                        TEST_CASE_EVAL_SCORES.COMPUTATION_ID.as(COMPUTATION_ID),
                        TEST_CASE_EVAL_SCORES.TEST_CASE_ID.as(TEST_CASE_ID),
                        TEST_CASE_EVAL_SCORES.EXECUTION_STATUS.as(STATUS),
                        TEST_CASE_EVAL_SCORES.PASSED.as(PASSED))
                .from(TEST_CASE_EVAL_SCORES)
                .where(DSL.row(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID, TEST_CASE_EVAL_SCORES.COMPUTATION_ID)
                        .in(pairs))
                .and(RunDeletionFilter.notDeleted(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID))
                .asTable("scores");

        final Field<String> casesRun = cases.field(RUN_ID, String.class);
        final Field<String> scoresRun = scores.field(RUN_ID, String.class);
        final Field<String> scoresTestCase = scores.field(TEST_CASE_ID, String.class);
        final Field<String> status = scores.field(STATUS, String.class);
        final Field<Boolean> passed = scores.field(PASSED, Boolean.class);
        final String success = ExecutionStatus.SUCCESS.name();

        final Field<String> runId = DSL.coalesce(casesRun, scoresRun);
        final Field<Integer> passedTrue = DSL.count().filterWhere(passed.isTrue());
        final Field<Integer> passedFalse = DSL.count().filterWhere(passed.isFalse());
        final Field<Integer> unscored = DSL.count()
                .filterWhere(scoresTestCase.isNull().or(passed.isNull().and(status.eq(success))));
        final Field<Integer> failed = DSL.count().filterWhere(status.ne(success));

        final Map<UUID, RunPassCounts> result = new HashMap<>();
        dsl.select(runId, passedTrue, passedFalse, unscored, failed)
                .from(cases)
                .fullOuterJoin(scores)
                .on(casesRun.eq(scoresRun)
                        .and(cases.field(COMPUTATION_ID, String.class).eq(scores.field(COMPUTATION_ID, String.class)))
                        .and(cases.field(TEST_CASE_ID, String.class).eq(scoresTestCase)))
                .groupBy(runId)
                .forEach(r -> {
                    final UUID id = UUID.fromString(r.value1());
                    result.put(id, new RunPassCounts(id, r.value2(), r.value3(), r.value4(), r.value5()));
                });
        return result;
    }
}
