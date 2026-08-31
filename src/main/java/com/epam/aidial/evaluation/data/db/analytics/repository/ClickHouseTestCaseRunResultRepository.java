package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_RUN_RESULTS;

import com.epam.aidial.evaluation.data.db.analytics.mapper.TestCaseRunResultRecordMapper;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresTestCaseRunResultRepository}. Reads are inherited unchanged — the
 * jOOQ query surface is rendered by the injected {@code analyticsDsl} (CLICKHOUSE dialect at runtime).
 * Only {@link #saveAll} differs: ClickHouse has no {@code ON CONFLICT}; deduplication is delegated to
 * the {@code ReplacingMergeTree} table engine (ordered by the same natural key used for the Postgres
 * {@code onConflict}), made visible to readers via session-wide {@code FINAL} (see the {@code
 * analyticsDsl} datasource connection-init SQL).
 */
@Slf4j
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseTestCaseRunResultRepository extends PostgresTestCaseRunResultRepository {

    public ClickHouseTestCaseRunResultRepository(
            @Qualifier("analyticsDsl") DSLContext dsl,
            TestCaseRunResultRecordMapper recordMapper,
            WhereBuilder whereBuilder) {
        super(dsl, recordMapper, whereBuilder);
    }

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
                        .set(TEST_CASE_RUN_RESULTS.REQUEST_INDEX, r.getRequestIndex())
                        .set(TEST_CASE_RUN_RESULTS.TOTAL_REQUESTS, r.getTotalRequests())
                        .set(TEST_CASE_RUN_RESULTS.TURN_INDEX, r.getTurnIndex())
                        .set(TEST_CASE_RUN_RESULTS.TOTAL_TURNS, r.getTotalTurns())
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
                        .set(TEST_CASE_RUN_RESULTS.CREATED_AT_MS, r.getCreatedAtMs()))
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} test case run results", results.size());
    }
}
