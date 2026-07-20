package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_RUN_RESULTS;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.mapper.EvalSummaryRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricAggregationResult;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricPath;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SelectLimitStep;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresEvalSummaryRepository implements EvalSummaryRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final EvalSummaryRecordMapper recordMapper;
    private final WhereBuilder whereBuilder;

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
                        .set(TEST_CASE_EVAL_SUMMARIES.TURN_INDEX, s.getTurnIndex())
                        .set(TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS, s.getTotalTurns())
                        .set(TEST_CASE_EVAL_SUMMARIES.MULTI_TURN_ID, toIdString(s.getMultiTurnId()))
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                                s.getComputationId().toString())
                        .set(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA, toJsonb(s.getTestCaseData()))
                        .set(TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS, toJsonb(s.getExtractedColumns()))
                        .set(
                                TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                                s.getExecutionStatus().name())
                        .set(TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS, s.getExecDurationMs())
                        .set(TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE, s.getResponseStatusCode())
                        .set(TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES, toJsonb(s.getMetricValues()))
                        .set(TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS, toJsonb(s.getMetricInfos()))
                        .set(TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS, toJsonb(s.getExtractionWarnings()))
                        .set(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS, s.getCreatedAtMs())
                        .set(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS, s.getComputedAtMs())
                        .onConflict(
                                TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                                TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                                TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                                TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                                TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                                TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS)
                        .doNothing())
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} eval summaries", summaries.size());
    }

    @Override
    public CursorPage<EvalSummary> findAll(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size) {
        return findAllInternal(filters, computationId, runCreatedAtMs, cursor, size, false, false);
    }

    @Override
    public CursorPage<EvalSummary> findAllForExport(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size) {
        return findAllInternal(filters, computationId, runCreatedAtMs, cursor, size, true, false);
    }

    @Override
    public CursorPage<EvalSummary> findAllForExportWithBodies(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size) {
        return findAllInternal(filters, computationId, runCreatedAtMs, cursor, size, true, true);
    }

    @Override
    public Optional<EvalSummary> findById(UUID id) {
        return dsl.select(List.of(
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS,
                        TEST_CASE_EVAL_SUMMARIES.MULTI_TURN_ID,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS,
                        TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS,
                        TEST_CASE_RUN_RESULTS.REQUEST_BODY,
                        TEST_CASE_RUN_RESULTS.RESPONSE_BODY))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .leftJoin(TEST_CASE_RUN_RESULTS)
                .on(TEST_CASE_RUN_RESULTS.ID.eq(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID))
                .where(TEST_CASE_EVAL_SUMMARIES.ID.eq(id.toString()))
                .fetchOptional(recordMapper::mapExportWithBodies);
    }

    @Override
    public long count(List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs) {
        Condition condition = buildBaseCondition(filters, computationId, runCreatedAtMs);
        Long count = dsl.selectCount()
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(condition)
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public List<MetricAggregationResult> aggregate(
            List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, List<MetricPath> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Collections.emptyList();
        }

        Condition condition = buildBaseCondition(filters, computationId, runCreatedAtMs);

        List<Field<?>> selectFields = new ArrayList<>();
        for (int i = 0; i < metrics.size(); i++) {
            MetricPath metric = metrics.get(i);
            Field<String> accessor = buildMetricAccessor(metric);
            selectFields.add(DSL.avg(accessor.cast(BigDecimal.class)).as("avg_" + i));
            selectFields.add(DSL.min(accessor.cast(BigDecimal.class)).as("min_" + i));
            selectFields.add(DSL.max(accessor.cast(BigDecimal.class)).as("max_" + i));
            selectFields.add(DSL.count(accessor).as("count_" + i));
        }

        Record row = dsl.select(selectFields)
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(condition)
                .fetchOne();

        if (row == null) {
            return Collections.emptyList();
        }

        List<MetricAggregationResult> results = new ArrayList<>(metrics.size());
        for (int i = 0; i < metrics.size(); i++) {
            MetricPath metric = metrics.get(i);
            BigDecimal avg = row.getValue("avg_" + i, BigDecimal.class);
            BigDecimal min = row.getValue("min_" + i, BigDecimal.class);
            BigDecimal max = row.getValue("max_" + i, BigDecimal.class);
            Long count = row.getValue("count_" + i, Long.class);
            results.add(new MetricAggregationResult(
                    metric.metricName(),
                    metric.outputName(),
                    avg != null ? avg.doubleValue() : null,
                    min != null ? min.doubleValue() : null,
                    max != null ? max.doubleValue() : null,
                    count));
        }
        return results;
    }

    private CursorPage<EvalSummary> findAllInternal(
            List<FilterCondition> filters,
            UUID computationId,
            Long runCreatedAtMs,
            Cursor cursor,
            int size,
            boolean includeExportColumns,
            boolean includeBodies) {
        Condition condition = buildBaseCondition(filters, computationId, runCreatedAtMs);
        if (cursor != null) {
            condition = condition.and(DSL.row(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS, TEST_CASE_EVAL_SUMMARIES.ID)
                    .lt(DSL.row(cursor.createdAt(), cursor.id().toString())));
        }

        List<EvalSummary> rows;
        if (includeBodies) {
            rows = buildExportWithBodiesQuery(condition).limit(size + 1).fetch(recordMapper::mapExportWithBodies);
        } else if (includeExportColumns) {
            rows = buildExportQuery(condition).limit(size + 1).fetch(recordMapper::mapExport);
        } else {
            rows = buildListQuery(condition).limit(size + 1).fetch(recordMapper::mapList);
        }

        boolean hasMore = rows.size() > size;
        List<EvalSummary> content = hasMore ? rows.subList(0, size) : rows;

        Cursor nextCursor = null;
        if (hasMore && !content.isEmpty()) {
            EvalSummary last = content.get(content.size() - 1);
            nextCursor = new Cursor(last.getCreatedAtMs(), last.getId());
        }

        return new CursorPage<>(List.copyOf(content), nextCursor, hasMore);
    }

    private SelectLimitStep<Record> buildListQuery(Condition condition) {
        return dsl.select(List.of(
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS,
                        TEST_CASE_EVAL_SUMMARIES.MULTI_TURN_ID,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(condition)
                .orderBy(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS.desc(), TEST_CASE_EVAL_SUMMARIES.ID.desc());
    }

    private SelectLimitStep<Record> buildExportQuery(Condition condition) {
        return dsl.select(List.of(
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS,
                        TEST_CASE_EVAL_SUMMARIES.MULTI_TURN_ID,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS,
                        TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(condition)
                .orderBy(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS.desc(), TEST_CASE_EVAL_SUMMARIES.ID.desc());
    }

    private SelectLimitStep<Record> buildExportWithBodiesQuery(Condition condition) {
        return dsl.select(List.of(
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS,
                        TEST_CASE_EVAL_SUMMARIES.MULTI_TURN_ID,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS,
                        TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS,
                        TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS,
                        TEST_CASE_RUN_RESULTS.REQUEST_BODY,
                        TEST_CASE_RUN_RESULTS.RESPONSE_BODY))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .leftJoin(TEST_CASE_RUN_RESULTS)
                .on(TEST_CASE_RUN_RESULTS.ID.eq(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID))
                .where(condition)
                .orderBy(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS.desc(), TEST_CASE_EVAL_SUMMARIES.ID.desc());
    }

    private Condition buildBaseCondition(List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs) {
        Condition condition =
                whereBuilder.build(filters != null ? filters : List.of(), FilterWhitelists.EVAL_SUMMARIES);
        condition = condition.and(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID.eq(computationId.toString()));
        if (runCreatedAtMs != null) {
            condition = condition.and(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS.eq(runCreatedAtMs));
        }
        return condition;
    }

    @SuppressWarnings("unchecked")
    private static Field<String> buildMetricAccessor(MetricPath metric) {
        // (metric_values -> :metricName ->> :outputName) — path components bound as params
        Field<JSONB> jsonbField = TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES;
        return DSL.field(
                "({0}->{1}->>{2})",
                String.class, jsonbField, DSL.val(metric.metricName()), DSL.val(metric.outputName()));
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }

    private static String toIdString(UUID id) {
        return id != null ? id.toString() : null;
    }
}
