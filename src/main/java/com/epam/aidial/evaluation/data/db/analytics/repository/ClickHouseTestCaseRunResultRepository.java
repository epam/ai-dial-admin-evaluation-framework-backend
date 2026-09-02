package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.TEST_CASE_RUN_RESULTS;

import com.epam.aidial.evaluation.data.db.analytics.mapper.TestCaseRunResultRecordMapper;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresTestCaseRunResultRepository}. Reads are inherited unchanged — the
 * jOOQ query surface is rendered by the injected {@code analyticsDsl} (CLICKHOUSE dialect at runtime).
 * Only {@link #saveAll} differs: ClickHouse has no {@code ON CONFLICT}; deduplication is delegated to
 * the {@code ReplacingMergeTree} table engine, made visible to readers via the {@code
 * clickhouse_setting_final=1} connection property (not a session-wide {@code SET}, which does not
 * persist across statements on the ClickHouse V2 HTTP driver — see {@code
 * AnalyticsClickHouseConfiguration}'s Javadoc for the verified mechanism, the single source of truth).
 *
 * <p>The engine's {@code ORDER BY} is {@code (test_suite_id, test_suite_run_id, test_case_id,
 * run_index, request_index, turn_index, created_at_ms)} — a <b>superset</b> of the Postgres unique key
 * {@code (test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms)}, not
 * the same key: {@code test_suite_id} has no counterpart in the PG constraint. Today the two keys
 * partition the table into identical dedup groups only because {@code test_suite_id} is functionally
 * dependent on {@code test_suite_run_id} (a run always belongs to exactly one suite); a future feature
 * that reassigns a run's {@code test_suite_id} would break that dependency and must revisit this
 * {@code ORDER BY}.
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
        // One prepared statement executed as a JDBC batch with BIND VALUES — never dsl.batch(List<Query>):
        // jOOQ's multi-query batch inlines parameters as static SQL, and ClickHouse interprets backslash
        // escapes inside string literals, silently corrupting any JSON payload whose string values contain
        // characters Jackson escapes (\n, \t, \", \\). See docs/patterns/clickhouse-analytics.md.
        BatchBindStep batch = dsl.batch(dsl.insertInto(
                        TEST_CASE_RUN_RESULTS,
                        TEST_CASE_RUN_RESULTS.ID,
                        TEST_CASE_RUN_RESULTS.TEST_SUITE_RUN_ID,
                        TEST_CASE_RUN_RESULTS.TEST_SUITE_ID,
                        TEST_CASE_RUN_RESULTS.TEST_CASE_ID,
                        TEST_CASE_RUN_RESULTS.TEST_CASE_NAME,
                        TEST_CASE_RUN_RESULTS.RUN_INDEX,
                        TEST_CASE_RUN_RESULTS.REQUEST_INDEX,
                        TEST_CASE_RUN_RESULTS.TOTAL_REQUESTS,
                        TEST_CASE_RUN_RESULTS.TURN_INDEX,
                        TEST_CASE_RUN_RESULTS.TOTAL_TURNS,
                        TEST_CASE_RUN_RESULTS.TEST_CASE_DATA,
                        TEST_CASE_RUN_RESULTS.REQUEST_BODY,
                        TEST_CASE_RUN_RESULTS.RESPONSE_BODY,
                        TEST_CASE_RUN_RESULTS.RESPONSE_STATUS_CODE,
                        TEST_CASE_RUN_RESULTS.EXECUTION_STATUS,
                        TEST_CASE_RUN_RESULTS.EXEC_STARTED_AT_MS,
                        TEST_CASE_RUN_RESULTS.EXEC_COMPLETED_AT_MS,
                        TEST_CASE_RUN_RESULTS.EXEC_DURATION_MS,
                        TEST_CASE_RUN_RESULTS.TRACE_ID,
                        TEST_CASE_RUN_RESULTS.EXTRACTED_COLUMNS,
                        TEST_CASE_RUN_RESULTS.EXTRACTION_WARNINGS,
                        TEST_CASE_RUN_RESULTS.RETRY_COUNT,
                        TEST_CASE_RUN_RESULTS.LOG_DETAILS,
                        TEST_CASE_RUN_RESULTS.CREATED_AT_MS)
                .values(new Object[24]));
        for (TestCaseRunResult r : results) {
            batch = batch.bind(
                    r.getId().toString(),
                    r.getTestSuiteRunId().toString(),
                    r.getTestSuiteId().toString(),
                    r.getTestCaseId().toString(),
                    r.getTestCaseName(),
                    r.getRunIndex(),
                    r.getRequestIndex(),
                    r.getTotalRequests(),
                    r.getTurnIndex(),
                    r.getTotalTurns(),
                    toJsonb(r.getTestCaseData()),
                    toJsonb(r.getRequestBody()),
                    toJsonb(r.getResponseBody()),
                    r.getResponseStatusCode(),
                    r.getExecutionStatus().name(),
                    r.getExecStartedAtMs(),
                    r.getExecCompletedAtMs(),
                    r.getExecDurationMs(),
                    r.getTraceId(),
                    toJsonb(r.getExtractedColumns()),
                    toJsonb(r.getExtractionWarnings()),
                    r.getRetryCount(),
                    toJsonb(r.getLogDetails()),
                    r.getCreatedAtMs());
        }
        batch.execute();
        log.debug("Batch inserted {} test case run results", results.size());
    }
}
