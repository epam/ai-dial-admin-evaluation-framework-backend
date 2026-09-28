package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;
import static org.jooq.impl.DSL.excluded;

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

    /**
     * Every row here is new-format ({@code eval_summary_id = NULL}), so the conflict target is the
     * partial unique index scoped to that discriminator, not the {@code id} primary key (each call mints
     * a fresh {@code id}, so the PK itself never conflicts). Legacy rows (real {@code eval_summary_id})
     * are never written or touched by this method.
     */
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
                        .where(TEST_CASE_EVAL_SCORES.EVAL_SUMMARY_ID.isNull())
                        .doUpdate()
                        .set(TEST_CASE_EVAL_SCORES.TEST_CASE_NAME, excluded(TEST_CASE_EVAL_SCORES.TEST_CASE_NAME))
                        .set(TEST_CASE_EVAL_SCORES.EXECUTION_STATUS, excluded(TEST_CASE_EVAL_SCORES.EXECUTION_STATUS))
                        .set(TEST_CASE_EVAL_SCORES.SCORE, excluded(TEST_CASE_EVAL_SCORES.SCORE))
                        .set(TEST_CASE_EVAL_SCORES.PASSED, excluded(TEST_CASE_EVAL_SCORES.PASSED))
                        .set(TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS, excluded(TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS)))
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch upserted {} eval summary scores", scores.size());
    }
}
