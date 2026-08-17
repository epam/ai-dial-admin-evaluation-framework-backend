package com.epam.aidial.evaluation.functional.helper;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.DATASETS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_METRIC_DEFINITIONS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class MetaTestDataHelper {

    private final TestSuiteRepository testSuiteRepository;
    private final TestSuiteRunRepository testSuiteRunRepository;
    private final TestSuiteMetricDefinitionRepository tsmdRepository;
    private final TestCaseRepository testCaseRepository;
    private final DatasetRepository datasetRepository;
    private final DSLContext metaDsl;

    /**
     * Creates a fresh {@link Dataset} with an empty schema. Convenience for tests that don't care
     * about dataset semantics but need a non-null {@code datasetId} to bind a suite to.
     */
    @Transactional("metaTransactionManager")
    public Dataset createDataset(String name) {
        return createDataset(name, "[]");
    }

    @Transactional("metaTransactionManager")
    public Dataset createDataset(String name, String schemaJson) {
        return createDataset(name, schemaJson, DatasetVisibility.PUBLIC);
    }

    @Transactional("metaTransactionManager")
    public Dataset createDataset(String name, String schemaJson, DatasetVisibility visibility) {
        return createDataset(name, schemaJson, visibility, null);
    }

    @Transactional("metaTransactionManager")
    public Dataset createDataset(String name, String schemaJson, DatasetVisibility visibility, String description) {
        Dataset dataset = Dataset.builder()
                .name(name)
                .description(description)
                .testCaseSchema(schemaJson != null ? schemaJson : "[]")
                .validationWarnings("[]")
                .valid(true)
                .visibility(visibility)
                .createdBy("test-user")
                .build();
        return datasetRepository.save(dataset);
    }

    /**
     * Sets {@code test_case_schema} on a dataset, bumping {@code version} via the repository's
     * dedicated method. Used by tests that need to drive a schema change without going through the
     * full update API.
     */
    public void updateDatasetSchema(UUID datasetId, String schemaJson) {
        datasetRepository.updateTestCaseSchema(datasetId, schemaJson != null ? schemaJson : "[]");
    }

    /**
     * Seeds {@code count} test cases into the dataset and returns their generated ids in
     * insertion order.
     */
    @Transactional("metaTransactionManager")
    public List<UUID> seedManyTestCasesInDataset(UUID datasetId, int count, boolean valid) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TestCase tc = TestCase.builder()
                    .datasetId(datasetId)
                    .testCaseName("Seeded " + UUID.randomUUID())
                    .data("{}")
                    .valid(valid)
                    .validationWarnings("[]")
                    .build();
            testCaseRepository.save(tc);
            ids.add(tc.getId());
        }
        return ids;
    }

    /**
     * Seeds a single test case with caller-supplied {@code name} and {@code data} JSON into the
     * dataset and returns its generated id. Lets tests embed file references in {@code data} to
     * exercise ref rewriting.
     */
    @Transactional("metaTransactionManager")
    public UUID seedTestCaseInDataset(UUID datasetId, String name, String dataJson) {
        TestCase tc = TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(name)
                .data(dataJson)
                .valid(true)
                .validationWarnings("[]")
                .build();
        testCaseRepository.save(tc);
        return tc.getId();
    }

    /**
     * Seeds a single multi-turn test case with caller-supplied {@code name} and {@code multiTurnDataJson}
     * (a JSON array of per-turn data maps) into the dataset, and returns its generated id. Mirrors
     * {@link #seedTestCaseInDataset}, but populates {@code multi_turn_data} instead of {@code data}.
     */
    @Transactional("metaTransactionManager")
    public UUID seedMultiTurnTestCaseInDataset(UUID datasetId, String name, String multiTurnDataJson) {
        TestCase tc = TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(name)
                .data("{}")
                .multiTurnData(multiTurnDataJson)
                .valid(true)
                .validationWarnings("[]")
                .build();
        testCaseRepository.save(tc);
        return tc.getId();
    }

    /**
     * Creates a test suite bound to a freshly minted dataset. Convenience overload for tests that
     * don't care which dataset the suite uses.
     */
    @Transactional("metaTransactionManager")
    public TestSuite createTestSuite(String name) {
        Dataset dataset = createDataset("DATASET_" + name);
        return createTestSuite(name, dataset.getId());
    }

    @Transactional("metaTransactionManager")
    public TestSuite createTestSuite(String name, UUID datasetId) {
        TestSuite suite = TestSuite.builder()
                .name(name)
                .createdBy("test-user")
                .datasetId(datasetId)
                .disabledTestCaseIds("[]")
                .responseColumns("[]")
                .inputBindings("[]")
                .additionalRequests("[]")
                .validationWarnings("[]")
                .valid(true)
                .build();
        return testSuiteRepository.save(suite);
    }

    /**
     * Creates a test suite with the given deployment reference JSON string stored in the
     * {@code deployment_ref} column. Intended for tests that filter by {@code deployment_ref::name}
     * and similar sub-fields via the QueryDSL.
     */
    @Transactional("metaTransactionManager")
    public TestSuite createTestSuiteWithDeploymentRef(String name, String deploymentRefJson) {
        Dataset dataset = createDataset("DATASET_" + name);
        TestSuite suite = TestSuite.builder()
                .name(name)
                .createdBy("test-user")
                .datasetId(dataset.getId())
                .deploymentRef(deploymentRefJson)
                .disabledTestCaseIds("[]")
                .responseColumns("[]")
                .inputBindings("[]")
                .additionalRequests("[]")
                .validationWarnings("[]")
                .valid(true)
                .build();
        return testSuiteRepository.save(suite);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteRun createTestSuiteRun(UUID suiteId) {
        return createTestSuiteRun(suiteId, RunStatus.COMPLETED);
    }

    /**
     * Variant with an explicit terminal status, for callers asserting that a feature is (or is not) gated on
     * how a run ended — the run comparison, for one, deliberately accepts a CANCELLED run.
     */
    @Transactional("metaTransactionManager")
    public TestSuiteRun createTestSuiteRun(UUID suiteId, RunStatus status) {
        TestSuite suite = testSuiteRepository.findById(suiteId).orElseThrow();
        Dataset dataset = datasetRepository.findById(suite.getDatasetId()).orElseThrow();
        String snapshotJson = String.format(
                "{\"snapshotVersion\":\"2\","
                        + "\"datasetRef\":{\"id\":\"%s\",\"version\":%d,\"name\":\"%s\"},"
                        + "\"testCaseSchema\":%s,\"responseColumns\":%s}",
                dataset.getId(),
                dataset.getVersion() == null ? 1L : dataset.getVersion(),
                dataset.getName() == null ? "" : dataset.getName().replace("\"", "\\\""),
                dataset.getTestCaseSchema() == null ? "[]" : dataset.getTestCaseSchema(),
                suite.getResponseColumns() == null ? "[]" : suite.getResponseColumns());
        TestSuiteRun run = TestSuiteRun.builder()
                .testSuiteId(suiteId)
                .testRunName("run-" + UUID.randomUUID())
                .status(status.name())
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(0)
                .suiteSnapshot(snapshotJson)
                .build();
        return testSuiteRunRepository.save(run);
    }

    /**
     * Creates a completed run with {@code suite_snapshot = NULL} ("legacy run"). The export
     * endpoint rejects these with HTTP 422 SNAPSHOT_SUITE_MISSING; this helper exists solely to
     * exercise that rejection path.
     */
    @Transactional("metaTransactionManager")
    public TestSuiteRun createLegacyTestSuiteRun(UUID suiteId) {
        TestSuiteRun run = TestSuiteRun.builder()
                .testSuiteId(suiteId)
                .testRunName("run-" + UUID.randomUUID())
                .status(RunStatus.COMPLETED.name())
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(0)
                .build();
        return testSuiteRunRepository.save(run);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteRun createPendingRun(UUID suiteId, String testRunName) {
        TestSuiteRun run = TestSuiteRun.builder()
                .testSuiteId(suiteId)
                .testRunName(testRunName)
                .status(RunStatus.PENDING.name())
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(0)
                .build();
        return testSuiteRunRepository.save(run);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteRun createRunningRun(UUID suiteId, String testRunName) {
        TestSuiteRun run = TestSuiteRun.builder()
                .testSuiteId(suiteId)
                .testRunName(testRunName)
                .status(RunStatus.RUNNING.name())
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(0)
                .build();
        return testSuiteRunRepository.save(run);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteRun createCompletedRunWithTimestamps(UUID suiteId) {
        long now = System.currentTimeMillis();
        TestSuiteRun run = TestSuiteRun.builder()
                .testSuiteId(suiteId)
                .testRunName("run-" + UUID.randomUUID())
                .status(RunStatus.COMPLETED.name())
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(1)
                .startedAt(now - 60_000)
                .completedAt(now)
                .build();
        return testSuiteRunRepository.save(run);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteMetricDefinition createTestSuiteMetricDefinition(
            UUID testSuiteId, UUID metricDeclarationId, UUID metricDeclarationVersionId, String name) {
        return createTestSuiteMetricDefinition(
                testSuiteId, metricDeclarationId, metricDeclarationVersionId, name, "[]", "[]");
    }

    @Transactional("metaTransactionManager")
    public TestSuiteMetricDefinition createTestSuiteMetricDefinition(
            UUID testSuiteId,
            UUID metricDeclarationId,
            UUID metricDeclarationVersionId,
            String name,
            String configBindings,
            String inputBindings) {
        return createTestSuiteMetricDefinition(
                testSuiteId,
                metricDeclarationId,
                metricDeclarationVersionId,
                name,
                configBindings,
                inputBindings,
                null);
    }

    /**
     * Same as the five-arg overload, plus an optional {@code condition} (JSONata gating whether the
     * metric runs per result row — e.g. {@code "request.last"}). Null/blank means the metric always runs.
     */
    @Transactional("metaTransactionManager")
    public TestSuiteMetricDefinition createTestSuiteMetricDefinition(
            UUID testSuiteId,
            UUID metricDeclarationId,
            UUID metricDeclarationVersionId,
            String name,
            String configBindings,
            String inputBindings,
            String condition) {
        TestSuiteMetricDefinition tsmd = TestSuiteMetricDefinition.builder()
                .testSuiteId(testSuiteId)
                .metricDeclarationId(metricDeclarationId)
                .metricDeclarationVersionId(metricDeclarationVersionId)
                .name(name)
                .enabled(true)
                .valid(true)
                .validationWarnings("[]")
                .configBindings(configBindings)
                .inputBindings(inputBindings)
                .condition(condition)
                .build();
        return tsmdRepository.save(tsmd);
    }

    @Transactional("metaTransactionManager")
    public void forceTsmdInvalid(UUID tsmdId, String warningsJson) {
        tsmdRepository.updateValidation(tsmdId, false, warningsJson);
    }

    @Transactional("metaTransactionManager")
    public void clearTestSuiteMetricDefinitions() {
        metaDsl.deleteFrom(TEST_SUITE_METRIC_DEFINITIONS).execute();
    }

    public long countMetricDefinitions(UUID testSuiteId) {
        return tsmdRepository.count(testSuiteId);
    }

    public Optional<TestSuiteMetricDefinition> findMetricDefinition(UUID id) {
        return tsmdRepository.findById(id);
    }

    public Optional<TestSuiteRun> findRun(UUID id) {
        return testSuiteRunRepository.findById(id);
    }

    /**
     * Returns the {@code datasetId} a test suite is bound to. Functional-test helpers that build
     * URLs under {@code /datasets/{datasetId}/test-cases} use this when only the suite id is known
     * at the call site.
     */
    public UUID getDatasetId(UUID testSuiteId) {
        return testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new AssertionError("Suite not found: " + testSuiteId))
                .getDatasetId();
    }

    /**
     * Appends the supplied ids to the suite's {@code disabled_test_case_ids} JSONB array via a
     * concatenation update. Used by bulk-patch fixtures that need a created test case to start out
     * disabled at the suite level (the pre-{@code introduce-dataset-entity} {@code enabled=false}
     * semantics).
     */
    public void appendDisabledTestCaseIds(UUID testSuiteId, List<UUID> testCaseIds) {
        if (testCaseIds == null || testCaseIds.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < testCaseIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(testCaseIds.get(i)).append('"');
        }
        sb.append(']');
        metaDsl.execute(
                "UPDATE test_suites SET disabled_test_case_ids = "
                        + "COALESCE(disabled_test_case_ids, '[]'::jsonb) || ?::jsonb "
                        + "WHERE id = ?",
                sb.toString(),
                testSuiteId.toString());
    }

    @Transactional("metaTransactionManager")
    public void deleteRun(UUID id) {
        testSuiteRunRepository.deleteById(id);
    }

    @Transactional("metaTransactionManager")
    public void forceSuiteInvalid(UUID suiteId) {
        testSuiteRepository.updateIsValid(suiteId, false);
    }

    /**
     * Sets the suite's {@code test_case_filter} JSONB directly, bypassing the write-time validation on
     * the suite API so tests can pin a filter on an already-configured runnable suite (whose dataset
     * and test cases must be preserved — the suite PUT path would rebind the dataset).
     */
    public void setSuiteTestCaseFilter(UUID suiteId, String filterJson) {
        metaDsl.update(TEST_SUITES)
                .set(TEST_SUITES.TEST_CASE_FILTER, filterJson != null ? toJsonb(filterJson) : null)
                .where(TEST_SUITES.ID.eq(suiteId.toString()))
                .execute();
    }

    /**
     * Sets the suite's {@code deployment_ref} JSONB directly, bypassing the write-time validation on
     * the suite API. {@link #createTestSuite(String)} / {@link #createTestSuite(String, UUID)} never
     * set one (they exist to seed TSMD/dataset fixtures, not to exercise create-suite validation), so a
     * caller that runs full DEPLOYMENT-suite hard validation against a fixture built that way (e.g.
     * {@code TestSuiteCloneService}) needs this to backfill a minimal non-null value first.
     */
    public void forceDeploymentRef(UUID suiteId, String deploymentRefJson) {
        metaDsl.update(TEST_SUITES)
                .set(TEST_SUITES.DEPLOYMENT_REF, toJsonb(deploymentRefJson))
                .where(TEST_SUITES.ID.eq(suiteId.toString()))
                .execute();
    }

    public void backdateRunUpdatedAt(UUID runId, long updatedAtMs) {
        metaDsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAtMs)
                .where(TEST_SUITE_RUNS.ID.eq(runId.toString()))
                .execute();
    }

    /**
     * Forces a specific {@code created_at_ms} on a run, bypassing the normal write path. Used by
     * export tests that need to pin the run-scoping keyset value (e.g. two runs created in the
     * same millisecond) to reproduce the deduplication hazard the {@code runId} filter injection
     * is supposed to close.
     */
    public void forceRunCreatedAt(UUID runId, long createdAtMs) {
        metaDsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.CREATED_AT_MS, createdAtMs)
                .where(TEST_SUITE_RUNS.ID.eq(runId.toString()))
                .execute();
    }

    /**
     * Directly updates the test-case schema on the dataset that the suite is bound to, plus the
     * suite's {@code response_columns} JSONB column. Test-case schema lives on the {@link Dataset}
     * since the {@code introduce-dataset-entity} change; this helper preserves the old call shape
     * while routing the schema write to the new owner.
     */
    public void updateSuiteSchema(UUID suiteId, String testCaseSchemaJson, String responseColumnsJson) {
        TestSuite suite = testSuiteRepository.findById(suiteId).orElseThrow();
        metaDsl.update(DATASETS)
                .set(DATASETS.TEST_CASE_SCHEMA, toJsonb(testCaseSchemaJson))
                .where(DATASETS.ID.eq(suite.getDatasetId().toString()))
                .execute();
        metaDsl.update(TEST_SUITES)
                .set(TEST_SUITES.RESPONSE_COLUMNS, toJsonb(responseColumnsJson))
                .where(TEST_SUITES.ID.eq(suiteId.toString()))
                .execute();
    }

    /**
     * Sets the {@code suite_snapshot} JSONB column on a run. Used by export tests that need to
     * exercise the snapshot-version mismatch path or assert the persisted snapshot wins over a
     * synthesized one.
     */
    public void setRunSuiteSnapshot(UUID runId, String snapshotJson) {
        metaDsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.SUITE_SNAPSHOT, toJsonb(snapshotJson))
                .where(TEST_SUITE_RUNS.ID.eq(runId.toString()))
                .execute();
    }

    /**
     * Repoints a run at a non-existent suite id while bypassing the FK constraint. Used by
     * export tests that need to reproduce the {@code suite_snapshot IS NULL AND live suite
     * missing} corruption state — a state the FK normally prevents via {@code ON DELETE CASCADE}.
     * Triggers are toggled off and back on around the UPDATE so the constraint remains intact
     * for all other rows in the table.
     */
    public void forceRunTestSuiteIdBypassingFk(UUID runId, UUID newSuiteId) {
        metaDsl.execute("ALTER TABLE test_suite_runs DISABLE TRIGGER ALL");
        try {
            metaDsl.update(TEST_SUITE_RUNS)
                    .set(TEST_SUITE_RUNS.TEST_SUITE_ID, newSuiteId.toString())
                    .where(TEST_SUITE_RUNS.ID.eq(runId.toString()))
                    .execute();
        } finally {
            metaDsl.execute("ALTER TABLE test_suite_runs ENABLE TRIGGER ALL");
        }
    }

    /**
     * Forces a specific {@code updated_at_ms} on a test case, bypassing the normal write path.
     * Used by revalidation concurrency tests to simulate a concurrent edit between the
     * revalidation read and the guarded write.
     */
    public void forceTestCaseUpdatedAt(UUID testCaseId, long updatedAtMs) {
        metaDsl.update(TEST_CASES)
                .set(TEST_CASES.UPDATED_AT_MS, updatedAtMs)
                .where(TEST_CASES.ID.eq(testCaseId.toString()))
                .execute();
    }

    /**
     * Seeds {@code count} test cases into the suite's dataset and returns their ids in insertion
     * order. The {@code enabled} flag is preserved at the suite level: when {@code false}, the new
     * ids are appended to the suite's {@code disabled_test_case_ids} JSONB array via a
     * concatenation update, mirroring the pre-{@code introduce-dataset-entity} semantics where
     * the {@code is_enabled} column was per-row.
     */
    @Transactional("metaTransactionManager")
    public List<UUID> seedManyTestCases(UUID testSuiteId, int count, boolean enabled) {
        TestSuite suite = testSuiteRepository.findById(testSuiteId).orElseThrow();
        List<UUID> ids = seedManyTestCasesInDataset(suite.getDatasetId(), count, true);
        if (!enabled && !ids.isEmpty()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(ids.get(i)).append('"');
            }
            sb.append(']');
            String additionsJson = sb.toString();
            metaDsl.execute(
                    "UPDATE test_suites SET disabled_test_case_ids = "
                            + "COALESCE(disabled_test_case_ids, '[]'::jsonb) || ?::jsonb "
                            + "WHERE id = ?",
                    additionsJson,
                    testSuiteId.toString());
        }
        return ids;
    }

    /**
     * Forces a test case row to {@code is_valid = false} with the supplied warnings JSON, bypassing
     * the normal validation pipeline. Used by tests that need a row marked invalid without
     * constructing a schema mismatch.
     */
    public void forceTestCaseInvalid(UUID testCaseId, String warningsJson) {
        metaDsl.update(TEST_CASES)
                .set(TEST_CASES.IS_VALID, false)
                .set(TEST_CASES.VALIDATION_WARNINGS, toJsonb(warningsJson != null ? warningsJson : "[]"))
                .where(TEST_CASES.ID.eq(testCaseId.toString()))
                .execute();
    }

    /**
     * Forces a test case's {@code multi_turn_data} column to caller-supplied raw JSON, bypassing the
     * API's write path and its shared, {@code NON_NULL}-inclusion {@code ObjectMapper} (which always
     * serializes a well-formed turn-array shape and drops explicit JSON nulls). Lets tests plant JSON the
     * normal path could never produce: a shape {@code ValidationWarningsSerializer.deserializeTurnsStrict}
     * cannot parse into turn maps (e.g. a JSON array of scalars), to exercise the unreadable-turn-array
     * guard; or a turn map containing an explicit JSON {@code null} value, to pin the drop as a known
     * trade-off. The JSON must still be valid (the column is {@code jsonb}); only its shape is under the
     * caller's control.
     */
    public void forceRawMultiTurnData(UUID testCaseId, String rawJson) {
        metaDsl.update(TEST_CASES)
                .set(TEST_CASES.MULTI_TURN_DATA, rawJson != null ? toJsonb(rawJson) : null)
                .where(TEST_CASES.ID.eq(testCaseId.toString()))
                .execute();
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }

    private static final String V1_22_PATH = "/db/migration/meta/POSTGRES/V1.22__IntroduceDataset.sql";
    private static final String BACKFILL_BEGIN = "-- BEGIN backfill suite_snapshot v2 shape";
    private static final String BACKFILL_END = "-- END backfill suite_snapshot v2 shape";

    /**
     * Executes the {@code suite_snapshot} backfill UPDATE that lives as step 11 of
     * {@code V1.22__IntroduceDataset.sql}. The SQL is loaded from the classpath copy of the
     * migration and extracted between explicit {@code BEGIN}/{@code END} marker comments so that
     * tests exercise the exact production statement (the marker contract is documented in
     * {@code suite-snapshot-dataset-backward-compatibility/tasks.md} 1.1).
     */
    public void applyV1_22SnapshotBackfillBlock() {
        String migrationSql;
        try (InputStream is = getClass().getResourceAsStream(V1_22_PATH)) {
            if (is == null) {
                throw new IllegalStateException(V1_22_PATH + " not found on classpath");
            }
            migrationSql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + V1_22_PATH, e);
        }
        int beginIdx = migrationSql.indexOf(BACKFILL_BEGIN);
        int endIdx = migrationSql.indexOf(BACKFILL_END);
        if (beginIdx < 0 || endIdx < 0 || endIdx <= beginIdx) {
            throw new IllegalStateException("Could not locate BEGIN/END backfill markers in " + V1_22_PATH);
        }
        int sqlStart = migrationSql.indexOf('\n', beginIdx) + 1;
        String backfillSql = migrationSql.substring(sqlStart, endIdx).trim();
        metaDsl.execute(backfillSql);
    }
}
