package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.TEST_CASE_EVAL_SUMMARIES;

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
import org.jooq.BatchBindStep;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresEvalSummaryRepository}. Reads are inherited unchanged except for
 * the spots where the inherited jOOQ construction does not translate to ClickHouse:
 *
 * <ul>
 *   <li>{@link #saveAll} — no {@code ON CONFLICT}; deduplication is delegated to the {@code
 *       ReplacingMergeTree} table engine (ordered by the same natural key used for the Postgres
 *       {@code onConflict}), made visible to readers via the {@code clickhouse_setting_final=1}
 *       connection property (not a session-wide {@code SET}, which does not persist across statements
 *       on the ClickHouse V2 HTTP driver — see {@code AnalyticsClickHouseConfiguration}'s Javadoc for
 *       the verified mechanism, the single source of truth).
 *   <li>the metric accessors used by {@link #aggregate} — Postgres' {@code ->}/{@code ->>} JSONB path
 *       operators do not exist on ClickHouse; the text accessor (presence counting) uses {@code
 *       JSONExtract} over the JSON text, the numeric accessor reads the typed {@code metric_values_map}
 *       acceleration twin.
 *   <li>the two {@code FILTER (WHERE ...)} aggregates used by {@link #countMatches} — ClickHouse does
 *       not support the standard SQL {@code FILTER} clause (confirmed by a render probe: jOOQ emits it
 *       verbatim on {@code SQLDialect.CLICKHOUSE}, which ClickHouse then rejects); replaced with {@code
 *       CASE WHEN} aggregates, which are equivalent and supported everywhere.
 *   <li>the case-folding of the run-comparison match key — ClickHouse's {@code lower} only folds ASCII,
 *       so two names differing solely in the case of a non-ASCII letter would fail to match each other
 *       (and sort inconsistently) where Postgres' locale-aware {@code lower} matches them; replaced with
 *       {@code lowerUTF8}, ClickHouse's Unicode-aware equivalent.
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
        // Bind-value batch, never dsl.batch(List<Query>) — the multi-query batch inlines parameters and
        // ClickHouse interprets backslash escapes in string literals, corrupting escaped JSON payloads.
        // See docs/patterns/clickhouse-analytics.md.
        BatchBindStep batch = dsl.batch(dsl.insertInto(
                        TEST_CASE_EVAL_SUMMARIES,
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.REQUEST_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_REQUESTS,
                        TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_EVAL_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS,
                        TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS)
                .values(new Object[23]));
        for (EvalSummary s : summaries) {
            batch = batch.bind(
                    s.getId().toString(),
                    s.getTestSuiteId().toString(),
                    s.getTestSuiteRunId().toString(),
                    s.getTestCaseRunResultId().toString(),
                    s.getTestCaseId().toString(),
                    s.getTestCaseName(),
                    s.getRunIndex(),
                    s.getRequestIndex(),
                    s.getTotalRequests(),
                    s.getTurnIndex(),
                    s.getTotalTurns(),
                    s.getComputationId().toString(),
                    toJsonb(s.getTestCaseData()),
                    toJsonb(s.getExtractedColumns()),
                    s.getExecutionStatus().name(),
                    s.getExecDurationMs(),
                    s.getMetricEvalDurationMs(),
                    s.getResponseStatusCode(),
                    toJsonb(s.getMetricValues()),
                    toJsonb(s.getMetricInfos()),
                    toJsonb(s.getExtractionWarnings()),
                    s.getCreatedAtMs(),
                    s.getComputedAtMs());
        }
        batch.execute();
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

    /**
     * Reads the typed acceleration twin ({@code metric_values_map}, MATERIALIZED from
     * {@code metric_values} at insert — see the CLICKHOUSE V1.1 migration) instead of re-parsing the
     * JSON text per row: map access hits typed columnar data, keeps both keys as bound parameters, and
     * yields NULL for absent keys and explicit-null values — the same shape the {@code JSONExtract}
     * form produces. The text accessor above stays on the raw JSON column: its job is presence
     * counting, where a non-numeric value must still count as present.
     */
    @Override
    protected Field<BigDecimal> buildNumericMetricAccessor(MetricPath metric) {
        return DSL.field(
                "{0}[{1}][{2}]",
                BigDecimal.class,
                DSL.field(DSL.name(TEST_CASE_EVAL_SUMMARIES.getName(), "metric_values_map")),
                DSL.val(metric.metricName()),
                DSL.val(metric.outputName()));
    }

    @Override
    protected Field<String> lowerName(Field<String> name) {
        return DSL.function("lowerUTF8", SQLDataType.VARCHAR, name);
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
