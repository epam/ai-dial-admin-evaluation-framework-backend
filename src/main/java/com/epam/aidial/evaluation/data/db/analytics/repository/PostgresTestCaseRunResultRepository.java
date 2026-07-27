package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_RUN_RESULTS;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.mapper.TestCaseRunResultRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseRunResultRepository implements TestCaseRunResultRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final TestCaseRunResultRecordMapper recordMapper;
    private final WhereBuilder whereBuilder;

    @Override
    public void saveAll(List<TestCaseRunResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        List<Query> queries = results.stream()
                .map(r -> (Query) dsl.insertInto(TEST_CASE_RUN_RESULTS)
                        .set(TEST_CASE_RUN_RESULTS.ID, r.getId().toString())
                        .set(
                                TEST_CASE_RUN_RESULTS.TEST_SUITE_RUN_ID,
                                r.getTestSuiteRunId().toString())
                        .set(
                                TEST_CASE_RUN_RESULTS.TEST_SUITE_ID,
                                r.getTestSuiteId().toString())
                        .set(
                                TEST_CASE_RUN_RESULTS.TEST_CASE_ID,
                                r.getTestCaseId().toString())
                        .set(TEST_CASE_RUN_RESULTS.TEST_CASE_NAME, r.getTestCaseName())
                        .set(TEST_CASE_RUN_RESULTS.RUN_INDEX, r.getRunIndex())
                        .set(TEST_CASE_RUN_RESULTS.TURN_INDEX, r.getTurnIndex())
                        .set(TEST_CASE_RUN_RESULTS.TOTAL_TURNS, r.getTotalTurns())
                        .set(TEST_CASE_RUN_RESULTS.REQUEST_INDEX, r.getRequestIndex())
                        .set(TEST_CASE_RUN_RESULTS.REQUEST_LABEL, r.getRequestLabel())
                        .set(TEST_CASE_RUN_RESULTS.TEST_CASE_DATA, toJsonb(r.getTestCaseData()))
                        .set(TEST_CASE_RUN_RESULTS.REQUEST_BODY, toJsonb(r.getRequestBody()))
                        .set(TEST_CASE_RUN_RESULTS.RESPONSE_BODY, toJsonb(r.getResponseBody()))
                        .set(TEST_CASE_RUN_RESULTS.RESPONSE_STATUS_CODE, r.getResponseStatusCode())
                        .set(
                                TEST_CASE_RUN_RESULTS.EXECUTION_STATUS,
                                r.getExecutionStatus().name())
                        .set(TEST_CASE_RUN_RESULTS.EXEC_STARTED_AT_MS, r.getExecStartedAtMs())
                        .set(TEST_CASE_RUN_RESULTS.EXEC_COMPLETED_AT_MS, r.getExecCompletedAtMs())
                        .set(TEST_CASE_RUN_RESULTS.EXEC_DURATION_MS, r.getExecDurationMs())
                        .set(TEST_CASE_RUN_RESULTS.TRACE_ID, r.getTraceId())
                        .set(TEST_CASE_RUN_RESULTS.EXTRACTED_COLUMNS, toJsonb(r.getExtractedColumns()))
                        .set(TEST_CASE_RUN_RESULTS.EXTRACTION_WARNINGS, toJsonb(r.getExtractionWarnings()))
                        .set(TEST_CASE_RUN_RESULTS.RETRY_COUNT, r.getRetryCount())
                        .set(TEST_CASE_RUN_RESULTS.LOG_DETAILS, toJsonb(r.getLogDetails()))
                        .set(TEST_CASE_RUN_RESULTS.CREATED_AT_MS, r.getCreatedAtMs())
                        .onConflict(
                                TEST_CASE_RUN_RESULTS.TEST_SUITE_RUN_ID,
                                TEST_CASE_RUN_RESULTS.TEST_CASE_ID,
                                TEST_CASE_RUN_RESULTS.RUN_INDEX,
                                TEST_CASE_RUN_RESULTS.TURN_INDEX,
                                // Must match the widened unique index exactly, or two chain requests of
                                // one test-case run would collide and the second write be silently dropped.
                                TEST_CASE_RUN_RESULTS.REQUEST_INDEX,
                                TEST_CASE_RUN_RESULTS.CREATED_AT_MS)
                        .doNothing())
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} test case run results", results.size());
    }

    @Override
    public CursorPage<TestCaseRunResult> findAll(
            List<FilterCondition> filters, Long runCreatedAtMs, Cursor cursor, int size) {
        Condition condition = buildBaseCondition(filters, runCreatedAtMs);
        if (cursor != null) {
            condition = condition.and(DSL.row(TEST_CASE_RUN_RESULTS.CREATED_AT_MS, TEST_CASE_RUN_RESULTS.ID)
                    .lt(DSL.row(cursor.createdAt(), cursor.id().toString())));
        }

        List<TestCaseRunResult> rows = dsl.selectFrom(TEST_CASE_RUN_RESULTS)
                .where(condition)
                .orderBy(TEST_CASE_RUN_RESULTS.CREATED_AT_MS.desc(), TEST_CASE_RUN_RESULTS.ID.desc())
                .limit(size + 1)
                .fetch(recordMapper::map);

        boolean hasMore = rows.size() > size;
        List<TestCaseRunResult> content = hasMore ? rows.subList(0, size) : rows;

        Cursor nextCursor = null;
        if (hasMore && !content.isEmpty()) {
            TestCaseRunResult last = content.get(content.size() - 1);
            nextCursor = new Cursor(last.getCreatedAtMs(), last.getId());
        }

        return new CursorPage<>(List.copyOf(content), nextCursor, hasMore);
    }

    @Override
    public Optional<TestCaseRunResult> findById(UUID id) {
        return dsl.selectFrom(TEST_CASE_RUN_RESULTS)
                .where(TEST_CASE_RUN_RESULTS.ID.eq(id.toString()))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public long count(List<FilterCondition> filters, Long runCreatedAtMs) {
        Condition condition = buildBaseCondition(filters, runCreatedAtMs);
        Long count =
                dsl.selectCount().from(TEST_CASE_RUN_RESULTS).where(condition).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    private Condition buildBaseCondition(List<FilterCondition> filters, Long runCreatedAtMs) {
        Condition condition =
                whereBuilder.build(filters != null ? filters : List.of(), FilterWhitelists.ANALYTICS_RESULTS);
        if (runCreatedAtMs != null) {
            condition = condition.and(TEST_CASE_RUN_RESULTS.CREATED_AT_MS.eq(runCreatedAtMs));
        }
        return condition;
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
