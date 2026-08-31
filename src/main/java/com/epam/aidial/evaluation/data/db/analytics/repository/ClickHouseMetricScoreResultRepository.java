package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.data.db.analytics.mapper.MetricScoreResultRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresMetricScoreResultRepository}. Reads are inherited unchanged. Only
 * {@link #saveAll} differs: ClickHouse has no {@code ON CONFLICT}; deduplication is delegated to the
 * {@code ReplacingMergeTree} table engine (ordered by the same natural key used for the Postgres
 * {@code onConflict}), made visible to readers via the {@code clickhouse_setting_final=1} connection
 * property (not a session-wide {@code SET}, which does not persist across statements on the
 * ClickHouse V2 HTTP driver — see {@code AnalyticsClickHouseConfiguration}'s Javadoc for the verified
 * mechanism, the single source of truth), and the {@code value} column is written through
 * {@link #exactFloat64} rather than as a plain bind.
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
                        .set(METRIC_SCORE_RESULT.VALUE, exactFloat64(r.getValue()))
                        .set(METRIC_SCORE_RESULT.COMPUTED_AT_MS, r.getComputedAtMs()))
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} metric score results", results.size());
    }

    /**
     * Renders {@code value} as {@code toFloat64('<plain decimal>')} so it survives the insert bit-for-bit.
     *
     * <p>jOOQ's multi-query batch inlines its bind values, and it inlines a {@code Double} in Java's
     * scientific notation ({@code 8.500000000000001E-1}). ClickHouse's textual {@code Float64} parser is one
     * ULP off for the exponent form — verified against a live server, where
     * {@code toFloat64('8.500000000000001E-1')} yields {@code 0.8500000000000002} while
     * {@code toFloat64('0.8500000000000001')} yields the exact original. Silently shifting a persisted
     * metric score by an ULP makes it disagree with the same statistic recomputed on the fly (which is what
     * the run-comparison endpoint does), so the value is inlined as an explicit plain-decimal string
     * instead. {@link BigDecimal#valueOf(double)} uses the shortest round-tripping representation, and
     * {@link BigDecimal#toPlainString()} guarantees no exponent.
     */
    private static Field<Double> exactFloat64(Double value) {
        if (value == null) {
            return DSL.inline((Double) null, SQLDataType.DOUBLE);
        }
        return DSL.field(
                "toFloat64({0})",
                SQLDataType.DOUBLE, DSL.inline(BigDecimal.valueOf(value).toPlainString()));
    }
}
