package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.data.db.analytics.mapper.MetricScoreResultRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
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
 * {@link #exactFloat64Bind()} rather than as a plain {@code Double} bind.
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
        // Bind-value batch, never dsl.batch(List<Query>) — the multi-query batch inlines parameters and
        // ClickHouse interprets backslash escapes in string literals, corrupting escaped text values.
        // See docs/patterns/clickhouse-analytics.md.
        BatchBindStep batch = dsl.batch(dsl.insertInto(
                        METRIC_SCORE_RESULT,
                        METRIC_SCORE_RESULT.ID,
                        METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID,
                        METRIC_SCORE_RESULT.TEST_SUITE_ID,
                        METRIC_SCORE_RESULT.COMPUTATION_ID,
                        METRIC_SCORE_RESULT.METRIC_SCORE_NAME,
                        METRIC_SCORE_RESULT.METRIC_NAME,
                        METRIC_SCORE_RESULT.COMPUTED_AT_MS,
                        METRIC_SCORE_RESULT.VALUE)
                .values(null, null, null, null, null, null, null, exactFloat64Bind()));
        for (MetricScoreResult r : results) {
            batch = batch.bind(
                    r.getId().toString(),
                    r.getTestSuiteRunId().toString(),
                    r.getTestSuiteId().toString(),
                    r.getComputationId().toString(),
                    r.getMetricScoreName(),
                    r.getMetricName(),
                    r.getComputedAtMs(),
                    plainDecimal(r.getValue()));
        }
        batch.execute();
        log.debug("Batch inserted {} metric score results", results.size());
    }

    /**
     * Writes {@code value} as {@code toFloat64(?)} with a bound <em>plain-decimal string</em> so it survives
     * the insert bit-for-bit regardless of how a {@code Double} would be rendered in transit.
     *
     * <p>A {@code Double} formatted in Java's scientific notation ({@code 8.500000000000001E-1}) trips
     * ClickHouse's textual {@code Float64} parser, which is one ULP off for the exponent form — verified
     * against a live server, where {@code toFloat64('8.500000000000001E-1')} yields
     * {@code 0.8500000000000002} while {@code toFloat64('0.8500000000000001')} yields the exact original.
     * Silently shifting a persisted metric score by an ULP makes it disagree with the same statistic
     * recomputed on the fly (which is what the run-comparison endpoint does), so the value crosses the wire
     * as an explicit plain-decimal string. {@link BigDecimal#valueOf(double)} uses the shortest
     * round-tripping representation, and {@link BigDecimal#toPlainString()} guarantees no exponent.
     * {@code toFloat64(NULL)} propagates NULL for absent scores.
     */
    private static Field<Double> exactFloat64Bind() {
        return DSL.field("toFloat64({0})", SQLDataType.DOUBLE, DSL.val((String) null));
    }

    private static String plainDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).toPlainString();
    }
}
