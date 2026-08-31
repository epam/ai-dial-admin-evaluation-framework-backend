package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.data.db.analytics.mapper.EvalSummaryRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricPath;
import com.epam.aidial.evaluation.data.db.repository.sql.ClickHouseTypeNames;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresEvalSummaryRepository}. Reads are inherited unchanged except for
 * two spots where the inherited jOOQ construction does not translate to ClickHouse:
 *
 * <ul>
 *   <li>{@link #saveAll} — no {@code ON CONFLICT}; deduplication is delegated to the {@code
 *       ReplacingMergeTree} table engine (ordered by the same natural key used for the Postgres
 *       {@code onConflict}), made visible to readers via the {@code clickhouse_setting_final=1}
 *       connection property (not a session-wide {@code SET}, which does not persist across statements
 *       on the ClickHouse V2 HTTP driver — see {@code AnalyticsClickHouseConfiguration}'s Javadoc for
 *       the verified mechanism, the single source of truth).
 *   <li>the metric accessor used by {@link #aggregate} — Postgres' {@code ->}/{@code ->>} JSONB path
 *       operators do not exist on ClickHouse; replaced with {@code JSONExtract}.
 *   <li>the two {@code FILTER (WHERE ...)} aggregates used by {@link #countMatches} — ClickHouse does
 *       not support the standard SQL {@code FILTER} clause (confirmed by a render probe: jOOQ emits it
 *       verbatim on {@code SQLDialect.CLICKHOUSE}, which ClickHouse then rejects); replaced with {@code
 *       CASE WHEN} aggregates, which are equivalent and supported everywhere.
 * </ul>
 *
 * <p>{@code existsByRunIdAndComputationId} is deliberately <b>not</b> overridden any more: the
 * inherited {@code fetchExists} sends {@code select exists (select 1 … where … = ?)}, which the
 * ClickHouse V2 driver's ANTLR statement parser could not parse on clickhouse-jdbc 0.9.0 (a {@code
 * SELECT} nested inside a scalar expression reported zero bind parameters and the bind failed before
 * any SQL reached the server). Verified fixed on 0.10.0 — {@code EvalSummaryExportFunctionalTests}
 * (which exercises this path) is green against a live server without an override.
 */
@Slf4j
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseEvalSummaryRepository extends PostgresEvalSummaryRepository {

    public ClickHouseEvalSummaryRepository(
            @Qualifier("analyticsDsl") DSLContext dsl,
            EvalSummaryRecordMapper recordMapper,
            WhereBuilder whereBuilder) {
        super(dsl, recordMapper, whereBuilder);
    }

    @Override
    public void saveAll(List<EvalSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        List<Query> queries = summaries.stream()
                .map(s -> (Query) dsl.insertInto(TEST_CASE_EVAL_SUMMARIES)
                        .set(TEST_CASE_EVAL_SUMMARIES.ID, s.getId().toString())
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                                s.getTestSuiteId().toString())
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                                s.getTestSuiteRunId().toString())
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                                s.getTestCaseRunResultId().toString())
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                                s.getTestCaseId().toString())
                        .set(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME, s.getTestCaseName())
                        .set(TEST_CASE_EVAL_SUMMARIES.RUN_INDEX, s.getRunIndex())
                        .set(TEST_CASE_EVAL_SUMMARIES.REQUEST_INDEX, s.getRequestIndex())
                        .set(TEST_CASE_EVAL_SUMMARIES.TOTAL_REQUESTS, s.getTotalRequests())
                        .set(TEST_CASE_EVAL_SUMMARIES.TURN_INDEX, s.getTurnIndex())
                        .set(TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS, s.getTotalTurns())
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                                s.getComputationId().toString())
                        .set(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA, toJsonb(s.getTestCaseData()))
                        .set(TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS, toJsonb(s.getExtractedColumns()))
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                                s.getExecutionStatus().name())
                        .set(TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS, s.getExecDurationMs())
                        .set(TEST_CASE_EVAL_SUMMARIES.METRIC_EVAL_DURATION_MS, s.getMetricEvalDurationMs())
                        .set(TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE, s.getResponseStatusCode())
                        .set(TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES, toJsonb(s.getMetricValues()))
                        .set(TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS, toJsonb(s.getMetricInfos()))
                        .set(TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS, toJsonb(s.getExtractionWarnings()))
                        .set(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS, s.getCreatedAtMs())
                        .set(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS, s.getComputedAtMs()))
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} eval summaries", summaries.size());
    }

    @Override
    protected Field<String> buildTextMetricAccessor(MetricPath metric) {
        // JSONExtract(metric_values, :metricName, :outputName, 'Nullable(String)') — keys stay bound
        // params (not inlined); only the type-name literal is a constant.
        return DSL.field(
                "JSONExtract({0}, {1}, {2}, '" + ClickHouseTypeNames.NULLABLE_STRING + "')",
                String.class,
                TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                DSL.val(metric.metricName()),
                DSL.val(metric.outputName()));
    }

    @Override
    protected Field<BigDecimal> buildNumericMetricAccessor(MetricPath metric) {
        return DSL.field(
                "JSONExtract({0}, {1}, {2}, '" + ClickHouseTypeNames.NULLABLE_FLOAT64 + "')",
                BigDecimal.class,
                TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                DSL.val(metric.metricName()),
                DSL.val(metric.outputName()));
    }

    @Override
    protected Field<Integer> matchedSuccessRowsField(Condition matched) {
        return DSL.count(DSL.case_()
                .when(matched.and(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS.eq(ExecutionStatus.SUCCESS.name())), 1));
    }

    @Override
    protected Field<BigDecimal> avgExecDurationMsField(Condition matched) {
        return DSL.avg(DSL.case_().when(matched, TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS));
    }
}
