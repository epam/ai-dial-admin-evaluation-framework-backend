package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryScore;
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
public class PostgresEvalSummaryScoreRepository implements EvalSummaryScoreRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    @Override
    public void saveAll(List<EvalSummaryScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        List<Query> queries = scores.stream()
                .map(s -> (Query) dsl.insertInto(TEST_CASE_EVAL_SCORES)
                        .set(
                                TEST_CASE_EVAL_SCORES.EVAL_SUMMARY_ID,
                                s.getEvalSummaryId().toString())
                        .set(TEST_CASE_EVAL_SCORES.SCORE, s.getScore())
                        .set(TEST_CASE_EVAL_SCORES.PASSED, s.getPassed())
                        .set(TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS, s.getComputedAtMs())
                        .onConflict(TEST_CASE_EVAL_SCORES.EVAL_SUMMARY_ID)
                        .doNothing())
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} eval summary scores", scores.size());
    }
}
