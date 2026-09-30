package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
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
}
