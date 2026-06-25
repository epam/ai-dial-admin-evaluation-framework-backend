package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.mapper.MetricScoreResultRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresMetricScoreResultRepository implements MetricScoreResultRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final MetricScoreResultRecordMapper recordMapper;

    @Override
    @Transactional("analyticsTransactionManager")
    public void saveAll(List<MetricScoreResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        List<Query> queries = results.stream()
                .map(r -> (Query) dsl.insertInto(METRIC_SCORE_RESULT)
                        .set(METRIC_SCORE_RESULT.ID, r.getId().toString())
                        .set(
                                METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID,
                                r.getTestSuiteRunId().toString())
                        .set(
                                METRIC_SCORE_RESULT.COMPUTATION_ID,
                                r.getComputationId().toString())
                        .set(METRIC_SCORE_RESULT.METRIC_SCORE_NAME, r.getMetricScoreName())
                        .set(METRIC_SCORE_RESULT.METRIC_NAME, r.getMetricName())
                        .set(METRIC_SCORE_RESULT.VALUE, r.getValue())
                        .onConflict(
                                METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID,
                                METRIC_SCORE_RESULT.COMPUTATION_ID,
                                METRIC_SCORE_RESULT.METRIC_SCORE_NAME,
                                METRIC_SCORE_RESULT.METRIC_NAME)
                        .doNothing())
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} metric score results", results.size());
    }

    @Override
    public List<MetricScoreResult> findByRunAndComputation(UUID runId, UUID computationId) {
        return dsl.selectFrom(METRIC_SCORE_RESULT)
                .where(METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(METRIC_SCORE_RESULT.COMPUTATION_ID.eq(computationId.toString()))
                .orderBy(METRIC_SCORE_RESULT.METRIC_SCORE_NAME.asc(), METRIC_SCORE_RESULT.METRIC_NAME.asc())
                .fetch(recordMapper::map);
    }
}
