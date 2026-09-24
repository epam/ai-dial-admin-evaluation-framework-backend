package com.epam.aidial.evaluation.query.service.metricscore;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseMetricScoreAggregatedBatchWriteItemDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record6;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Aggregates a test case's {@code test_case_eval_summaries} rows for a computation — every
 * {@code run_index}/{@code request_index}/{@code turn_index} combination collapsed together — into one
 * per-metric {@code avg}/{@code min}/{@code max}/{@code count} JSONB map, one row per test case.
 *
 * <p>Built with hand-written jOOQ against {@code analyticsDsl}, not the generic Query DSL translator:
 * the {@code jsonb_object_agg} shape this needs isn't expressible as an {@code OverallScoreDefinition}-style
 * query, and this component has nothing to do with scoring — it is a data-normalization step feeding
 * {@code test_case_metric_scores_aggregated}.
 *
 * <p>{@code metric_values} is a two-level JSONB map ({@code {"<tsmdName>": {"<outputField>": value}}}).
 * Rather than have Java enumerate every discovered metric field and {@code UNION ALL} one arm per field
 * (one scan of {@code test_case_eval_summaries} per field), this walks the JSONB generically in a single
 * scan via two chained {@code jsonb_each} calls (mirroring the raw-SQL table-function pattern used by
 * {@code QueryDslRunnableTestCaseSelector#compile}), filtering to numeric leaves with {@code jsonb_typeof}.
 * This is a deliberate, accepted shift from schema-declared numeric-field detection to runtime type
 * detection: a value that doesn't match its declared schema type is silently excluded here instead of
 * throwing a cast error, consistent with this table's fail-soft, regenerable-derived-data philosophy.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseMetricScoreAggregator {

    private static final String FIELD_TEST_CASE_ID = "test_case_id";
    private static final String FIELD_METRIC_NAME = "metric_name";
    private static final String FIELD_AVG_SCORE = "avg_score";
    private static final String FIELD_MIN_SCORE = "min_score";
    private static final String FIELD_MAX_SCORE = "max_score";
    private static final String FIELD_SAMPLE_COUNT = "sample_count";
    private static final String CTE_NAME = "metric_stats";
    private static final String TSMD_TABLE_ALIAS = "m";
    private static final String OUTPUT_FIELD_TABLE_ALIAS = "f";
    private static final String KEY_COLUMN = "key";
    private static final String VALUE_COLUMN = "value";
    private static final String NUMBER_TYPE = "number";
    private static final String METRIC_NAME_SEPARATOR = ".";

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    /**
     * Re-aggregates the <strong>entire</strong> row set (not just the current flush batch) of every id
     * in {@code testCaseIds}, for the given run/computation — required for correctness since one test
     * case's rows can straddle multiple flush batches. A metric field with zero (numeric) samples for a
     * test case is excluded from that test case's {@code metric_scores} map (never a zero/null entry),
     * since only rows whose value is JSON-numeric contribute a group at all.
     *
     * @return one item per test case that has at least one numeric sample for at least one metric field;
     *     a test case with no such samples is simply absent from the result
     */
    public List<TestCaseMetricScoreAggregatedBatchWriteItemDto> aggregate(
            UUID runId, UUID computationId, List<UUID> testCaseIds) {
        if (testCaseIds.isEmpty()) {
            return List.of();
        }
        List<String> testCaseIdStrings =
                testCaseIds.stream().map(UUID::toString).toList();

        Table<?> tsmdEach = DSL.table("jsonb_each({0})", TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES)
                .as(TSMD_TABLE_ALIAS, KEY_COLUMN, VALUE_COLUMN);
        Field<String> tsmdName = DSL.field(DSL.name(TSMD_TABLE_ALIAS, KEY_COLUMN), String.class);
        Field<JSONB> tsmdValue = DSL.field(DSL.name(TSMD_TABLE_ALIAS, VALUE_COLUMN), JSONB.class);

        Table<?> outputFieldEach =
                DSL.table("jsonb_each({0})", tsmdValue).as(OUTPUT_FIELD_TABLE_ALIAS, KEY_COLUMN, VALUE_COLUMN);
        Field<String> outputField = DSL.field(DSL.name(OUTPUT_FIELD_TABLE_ALIAS, KEY_COLUMN), String.class);
        Field<JSONB> outputValue = DSL.field(DSL.name(OUTPUT_FIELD_TABLE_ALIAS, VALUE_COLUMN), JSONB.class);

        Field<Double> valueField = DSL.field("({0})::text::double precision", Double.class, outputValue);
        Field<String> metricName = tsmdName.concat(METRIC_NAME_SEPARATOR).concat(outputField);
        Field<Double> avg = DSL.avg(valueField).cast(Double.class);
        Field<Double> min = DSL.min(valueField);
        Field<Double> max = DSL.max(valueField);
        Field<Integer> count = DSL.count();

        Select<Record6<String, String, Double, Double, Double, Integer>> perTestCaseMetricStats = dsl.select(
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        metricName.as(FIELD_METRIC_NAME),
                        avg.as(FIELD_AVG_SCORE),
                        min.as(FIELD_MIN_SCORE),
                        max.as(FIELD_MAX_SCORE),
                        count.as(FIELD_SAMPLE_COUNT))
                .from(TEST_CASE_EVAL_SUMMARIES, tsmdEach, outputFieldEach)
                .where(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID.eq(computationId.toString()))
                .and(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID.in(testCaseIdStrings))
                .and(DSL.field("jsonb_typeof({0})", String.class, outputValue).eq(NUMBER_TYPE))
                .groupBy(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID, tsmdName, outputField);

        CommonTableExpression<Record6<String, String, Double, Double, Double, Integer>> metricStats =
                DSL.name(CTE_NAME).as(perTestCaseMetricStats);
        Field<String> testCaseIdField = metricStats.field(FIELD_TEST_CASE_ID, String.class);
        Field<String> metricNameField = metricStats.field(FIELD_METRIC_NAME, String.class);
        Field<Double> avgField = metricStats.field(FIELD_AVG_SCORE, Double.class);
        Field<Double> minField = metricStats.field(FIELD_MIN_SCORE, Double.class);
        Field<Double> maxField = metricStats.field(FIELD_MAX_SCORE, Double.class);
        Field<Integer> countField = metricStats.field(FIELD_SAMPLE_COUNT, Integer.class);

        Field<JSONB> perMetricObject = DSL.function(
                "jsonb_build_object",
                JSONB.class,
                DSL.val("avg"),
                avgField,
                DSL.val("min"),
                minField,
                DSL.val("max"),
                maxField,
                DSL.val("count"),
                countField);
        Field<JSONB> metricScores = DSL.function("jsonb_object_agg", JSONB.class, metricNameField, perMetricObject);

        return dsl.with(metricStats)
                .select(testCaseIdField, metricScores)
                .from(metricStats)
                .groupBy(testCaseIdField)
                .fetch(r -> TestCaseMetricScoreAggregatedBatchWriteItemDto.builder()
                        .testSuiteRunId(runId)
                        .testCaseId(UUID.fromString(r.value1()))
                        .computationId(computationId)
                        .metricScores(r.value2().data())
                        .build());
    }
}
