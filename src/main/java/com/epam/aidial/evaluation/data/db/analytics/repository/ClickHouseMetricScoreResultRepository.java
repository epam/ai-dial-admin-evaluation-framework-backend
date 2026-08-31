package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.data.db.analytics.mapper.MetricScoreResultRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresMetricScoreResultRepository}. Reads are inherited unchanged. Only
 * {@link #saveAll} differs: ClickHouse has no {@code ON CONFLICT}; deduplication is delegated to the
 * {@code ReplacingMergeTree} table engine (ordered by the same natural key used for the Postgres
 * {@code onConflict}), made visible to readers via session-wide {@code FINAL}.
 */
@Slf4j
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseMetricScoreResultRepository extends PostgresMetricScoreResultRepository {

    public ClickHouseMetricScoreResultRepository(
            @Qualifier("analyticsDsl") DSLContext dsl, MetricScoreResultRecordMapper recordMapper) {
        super(dsl, recordMapper);
    }

    @Override
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
                                METRIC_SCORE_RESULT.TEST_SUITE_ID,
                                r.getTestSuiteId().toString())
                        .set(
                                METRIC_SCORE_RESULT.COMPUTATION_ID,
                                r.getComputationId().toString())
                        .set(METRIC_SCORE_RESULT.METRIC_SCORE_NAME, r.getMetricScoreName())
                        .set(METRIC_SCORE_RESULT.METRIC_NAME, r.getMetricName())
                        .set(METRIC_SCORE_RESULT.VALUE, r.getValue())
                        .set(METRIC_SCORE_RESULT.COMPUTED_AT_MS, r.getComputedAtMs()))
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} metric score results", results.size());
    }
}
