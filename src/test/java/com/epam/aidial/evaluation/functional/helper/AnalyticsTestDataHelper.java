package com.epam.aidial.evaluation.functional.helper;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_METRIC_SNAPSHOTS;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_RUN_RESULTS;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class AnalyticsTestDataHelper {

    private final DSLContext analyticsDsl;

    @Transactional("analyticsTransactionManager")
    public void cleanupResults() {
        analyticsDsl.deleteFrom(TEST_CASE_RUN_RESULTS).execute();
    }

    public long countAll() {
        Long count = analyticsDsl.selectCount().from(TEST_CASE_RUN_RESULTS).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    public Optional<UUID> findAnyResultId() {
        return analyticsDsl
                .select(TEST_CASE_RUN_RESULTS.ID)
                .from(TEST_CASE_RUN_RESULTS)
                .limit(1)
                .fetchOptional(r -> UUID.fromString(r.getValue(TEST_CASE_RUN_RESULTS.ID)));
    }

    public Optional<Long> findAnyResultCreatedAt() {
        return analyticsDsl
                .select(TEST_CASE_RUN_RESULTS.CREATED_AT_MS)
                .from(TEST_CASE_RUN_RESULTS)
                .limit(1)
                .fetchOptional(r -> r.getValue(TEST_CASE_RUN_RESULTS.CREATED_AT_MS));
    }

    /**
     * Reads an index definition out of the {@code pg_indexes} catalog view. The view has no generated
     * jOOQ table, so it is referenced through plain-SQL field/table names — still the analytics
     * {@code DSLContext}, and the SQL stays inside this helper.
     *
     * @return the {@code indexdef} of the index, or empty when the table has no such index
     */
    public Optional<String> findIndexDefinition(String tableName, String indexName) {
        return analyticsDsl
                .select(DSL.field("indexdef", String.class))
                .from(DSL.table("pg_indexes"))
                .where(DSL.field("tablename", String.class).eq(tableName))
                .and(DSL.field("indexname", String.class).eq(indexName))
                .fetchOptional(r -> r.getValue(DSL.field("indexdef", String.class)));
    }

    @Transactional("analyticsTransactionManager")
    public void cleanupEvalSummaries() {
        analyticsDsl.deleteFrom(TEST_CASE_EVAL_SUMMARIES).execute();
    }

    @Transactional("analyticsTransactionManager")
    public void cleanupRunMetricSnapshots() {
        analyticsDsl.deleteFrom(RUN_METRIC_SNAPSHOTS).execute();
    }

    public long countEvalSummaries() {
        Long count = analyticsDsl.selectCount().from(TEST_CASE_EVAL_SUMMARIES).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    public long countRunMetricSnapshots() {
        Long count = analyticsDsl.selectCount().from(RUN_METRIC_SNAPSHOTS).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    public List<Map<String, Object>> findEvalSummariesByRunId(UUID runId) {
        return analyticsDsl
                .select(
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.REQUEST_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_REQUESTS,
                        TEST_CASE_EVAL_SUMMARIES.TURN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID)
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .fetch(AnalyticsTestDataHelper::recordToMap);
    }

    public List<Map<String, Object>> findResultsByRunId(UUID runId) {
        return analyticsDsl
                .select(
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
                        TEST_CASE_RUN_RESULTS.EXECUTION_STATUS,
                        TEST_CASE_RUN_RESULTS.REQUEST_BODY,
                        TEST_CASE_RUN_RESULTS.RESPONSE_BODY,
                        TEST_CASE_RUN_RESULTS.RESPONSE_STATUS_CODE,
                        TEST_CASE_RUN_RESULTS.EXTRACTED_COLUMNS,
                        TEST_CASE_RUN_RESULTS.RETRY_COUNT)
                .from(TEST_CASE_RUN_RESULTS)
                .where(TEST_CASE_RUN_RESULTS.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .fetch(AnalyticsTestDataHelper::recordToMap);
    }

    /**
     * Inserts a minimal test_case_run_result row with the given request/response bodies.
     *
     * @return the inserted run result ID
     */
    @Transactional("analyticsTransactionManager")
    public UUID createTestRunResult(
            UUID suiteRunId,
            UUID suiteId,
            UUID testCaseId,
            String testCaseName,
            String requestBodyJson,
            String responseBodyJson,
            long createdAtMs) {
        UUID id = UUID.randomUUID();
        analyticsDsl
                .insertInto(TEST_CASE_RUN_RESULTS)
                .set(TEST_CASE_RUN_RESULTS.ID, id.toString())
                .set(TEST_CASE_RUN_RESULTS.TEST_SUITE_RUN_ID, suiteRunId.toString())
                .set(TEST_CASE_RUN_RESULTS.TEST_SUITE_ID, suiteId.toString())
                .set(TEST_CASE_RUN_RESULTS.TEST_CASE_ID, testCaseId.toString())
                .set(TEST_CASE_RUN_RESULTS.TEST_CASE_NAME, testCaseName)
                .set(TEST_CASE_RUN_RESULTS.RUN_INDEX, 0)
                .set(TEST_CASE_RUN_RESULTS.TEST_CASE_DATA, JSONB.valueOf("{}"))
                .set(TEST_CASE_RUN_RESULTS.REQUEST_BODY, toJsonb(requestBodyJson))
                .set(TEST_CASE_RUN_RESULTS.RESPONSE_BODY, toJsonb(responseBodyJson))
                .set(TEST_CASE_RUN_RESULTS.EXECUTION_STATUS, ExecutionStatus.SUCCESS.name())
                .set(TEST_CASE_RUN_RESULTS.EXEC_STARTED_AT_MS, createdAtMs)
                .set(TEST_CASE_RUN_RESULTS.EXEC_COMPLETED_AT_MS, createdAtMs)
                .set(TEST_CASE_RUN_RESULTS.EXEC_DURATION_MS, 100L)
                .set(TEST_CASE_RUN_RESULTS.CREATED_AT_MS, createdAtMs)
                .execute();
        return id;
    }

    /**
     * Inserts a minimal {@code test_case_eval_summaries} row. Columns not exposed as parameters are
     * filled with deterministic placeholders (random natural-key UUIDs, run index 0, empty JSONB);
     * columns with DB defaults ({@code extracted_columns}, {@code metric_values},
     * {@code extraction_warnings}) and nullable columns ({@code metric_infos},
     * {@code response_status_code}) are left to the database.
     *
     * @return the inserted eval summary ID
     */
    @Transactional("analyticsTransactionManager")
    public UUID createEvalSummary(
            UUID suiteId,
            UUID suiteRunId,
            UUID computationId,
            String testCaseName,
            String executionStatus,
            long execDurationMs,
            long createdAtMs) {
        return createEvalSummary(
                suiteId,
                suiteRunId,
                computationId,
                testCaseName,
                executionStatus,
                execDurationMs,
                createdAtMs,
                "{}",
                "{}");
    }

    /**
     * Variant carrying explicit {@code test_case_data} and {@code metric_values} JSON, for exercising
     * the flattened {@code data:}/{@code metric:} field paths. {@code computed_at_ms} mirrors
     * {@code created_at_ms}.
     */
    @Transactional("analyticsTransactionManager")
    public UUID createEvalSummary(
            UUID suiteId,
            UUID suiteRunId,
            UUID computationId,
            String testCaseName,
            String executionStatus,
            long execDurationMs,
            long createdAtMs,
            String testCaseDataJson,
            String metricValuesJson) {
        return createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(suiteRunId)
                .computationId(computationId)
                .testCaseName(testCaseName)
                .executionStatus(executionStatus)
                .execDurationMs(execDurationMs)
                .createdAtMs(createdAtMs)
                .testCaseDataJson(testCaseDataJson)
                .metricValuesJson(metricValuesJson)
                .build());
    }

    /**
     * Full-control variant: the only one that can vary {@code run_index} / {@code turn_index} /
     * {@code total_turns}, which together with the (lower-cased) test case name form the run-comparison
     * match key. The positional overloads above delegate here with those three at their defaults.
     *
     * @return the inserted eval summary ID
     */
    @Transactional("analyticsTransactionManager")
    public UUID createEvalSummary(EvalSummaryFixture fixture) {
        UUID id = UUID.randomUUID();
        analyticsDsl
                .insertInto(TEST_CASE_EVAL_SUMMARIES)
                .set(TEST_CASE_EVAL_SUMMARIES.ID, id.toString())
                .set(
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        fixture.getSuiteId().toString())
                .set(
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        fixture.getRunId().toString())
                .set(
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        UUID.randomUUID().toString())
                .set(
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        fixture.getTestCaseId().toString())
                .set(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME, fixture.getTestCaseName())
                .set(TEST_CASE_EVAL_SUMMARIES.RUN_INDEX, fixture.getRunIndex())
                .set(TEST_CASE_EVAL_SUMMARIES.REQUEST_INDEX, fixture.getRequestIndex())
                .set(TEST_CASE_EVAL_SUMMARIES.TURN_INDEX, fixture.getTurnIndex())
                .set(TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS, fixture.getTotalTurns())
                .set(
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        fixture.getComputationId().toString())
                .set(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA, JSONB.valueOf(fixture.getTestCaseDataJson()))
                .set(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS, fixture.getExecutionStatus())
                .set(TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS, fixture.getExecDurationMs())
                .set(TEST_CASE_EVAL_SUMMARIES.METRIC_EVAL_DURATION_MS, fixture.getMetricEvalDurationMs())
                .set(TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES, JSONB.valueOf(fixture.getMetricValuesJson()))
                .set(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS, fixture.getCreatedAtMs())
                .set(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS, fixture.getComputedAtMs())
                .execute();
        return id;
    }

    /**
     * Inserts {@code count} eval summaries with distinct, sequentially numbered test case names, in one
     * batch. Exists for the run-comparison cap test, which needs thousands of rows that match nothing: at one
     * statement per row that fixture dominates the suite's runtime, and the rows carry no individuality
     * worth expressing.
     */
    @Transactional("analyticsTransactionManager")
    public void createDistinctlyNamedEvalSummaries(
            UUID suiteId, UUID suiteRunId, UUID computationId, String namePrefix, int count, long createdAtMs) {
        BatchBindStep batch = analyticsDsl.batch(analyticsDsl
                .insertInto(
                        TEST_CASE_EVAL_SUMMARIES,
                        TEST_CASE_EVAL_SUMMARIES.ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                        TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID,
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                        TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                        TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                        TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                        TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS,
                        TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS)
                .values((String) null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        for (int i = 0; i < count; i++) {
            batch = batch.bind(
                    UUID.randomUUID().toString(),
                    suiteId.toString(),
                    suiteRunId.toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    namePrefix + i,
                    0,
                    computationId.toString(),
                    JSONB.valueOf("{}"),
                    ExecutionStatus.SUCCESS.name(),
                    100L,
                    JSONB.valueOf("{}"),
                    createdAtMs,
                    createdAtMs);
        }
        batch.execute();
    }

    /**
     * Inserts a minimal {@code run_metric_snapshots} row for a run/computation. Identity columns not
     * exposed as parameters ({@code tsmd_id}, {@code metric_declaration_id},
     * {@code metric_declaration_version_id}) get random UUIDs; {@code config_bindings}/
     * {@code input_bindings} are left to their DB defaults.
     *
     * @return the inserted run metric snapshot ID
     */
    @Transactional("analyticsTransactionManager")
    public UUID createRunMetricSnapshot(
            UUID suiteRunId, UUID computationId, String tsmdName, String outputSchemaJson, long computedAtMs) {
        UUID id = UUID.randomUUID();
        analyticsDsl
                .insertInto(RUN_METRIC_SNAPSHOTS)
                .set(RUN_METRIC_SNAPSHOTS.ID, id.toString())
                .set(RUN_METRIC_SNAPSHOTS.COMPUTATION_ID, computationId.toString())
                .set(RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID, suiteRunId.toString())
                .set(RUN_METRIC_SNAPSHOTS.TSMD_ID, UUID.randomUUID().toString())
                .set(RUN_METRIC_SNAPSHOTS.TSMD_NAME, tsmdName)
                .set(
                        RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_ID,
                        UUID.randomUUID().toString())
                .set(
                        RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_VERSION_ID,
                        UUID.randomUUID().toString())
                .set(RUN_METRIC_SNAPSHOTS.OUTPUT_SCHEMA, JSONB.valueOf(outputSchemaJson))
                .set(RUN_METRIC_SNAPSHOTS.COMPUTED_AT_MS, computedAtMs)
                .execute();
        return id;
    }

    public List<Map<String, Object>> findRunMetricSnapshotsByRunId(UUID runId) {
        return analyticsDsl
                .select(
                        RUN_METRIC_SNAPSHOTS.ID,
                        RUN_METRIC_SNAPSHOTS.COMPUTATION_ID,
                        RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID,
                        RUN_METRIC_SNAPSHOTS.TSMD_ID,
                        RUN_METRIC_SNAPSHOTS.TSMD_NAME,
                        RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_ID,
                        RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_VERSION_ID,
                        RUN_METRIC_SNAPSHOTS.CONFIG_BINDINGS,
                        RUN_METRIC_SNAPSHOTS.INPUT_BINDINGS,
                        RUN_METRIC_SNAPSHOTS.OUTPUT_SCHEMA)
                .from(RUN_METRIC_SNAPSHOTS)
                .where(RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .fetch(AnalyticsTestDataHelper::recordToMap);
    }

    /**
     * Converts a jOOQ {@link Record} to a {@link Map}, unwrapping {@link JSONB} values to their
     * raw JSON strings so that test assertions can use plain {@code String} casts.
     */
    private static Map<String, Object> recordToMap(Record record) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < record.size(); i++) {
            String fieldName = record.field(i).getName();
            Object value = record.get(i);
            map.put(fieldName, value instanceof JSONB jsonb ? jsonb.data() : value);
        }
        return map;
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
