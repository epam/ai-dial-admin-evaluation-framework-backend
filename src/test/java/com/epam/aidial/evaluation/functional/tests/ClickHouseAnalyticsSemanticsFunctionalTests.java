package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * ClickHouse-only invariants that have no Postgres counterpart, because on Postgres the same guarantees
 * come from a unique constraint plus {@code ON CONFLICT DO NOTHING} rather than from vendor settings, plus
 * one regression guard specific to this vendor's schema-management history:
 *
 * <ul>
 *   <li><b>Dedup-exact reads.</b> ClickHouse writes are plain {@code INSERT}s and duplicates are collapsed
 *       by the {@code ReplacingMergeTree} engine, which merges in the background — so a reader sees one row
 *       per natural key only because the analytics datasource pins the server setting {@code final=1}. If
 *       that setting ever stops reaching the server, this test sees two rows.
 *   <li><b>Float64 write fidelity.</b> jOOQ's batch inlines a {@code Double} in scientific notation and
 *       ClickHouse's textual {@code Float64} parser is one ULP off for that form, so persisted metric scores
 *       have to be written as an explicit plain decimal.
 *   <li><b>Flyway schema ownership.</b> An earlier revision of this vendor applied its schema with a
 *       hand-rolled initializer (no history table, every script re-run on every boot) because the Flyway
 *       ClickHouse plugin couldn't run on clickhouse-jdbc 0.9.0. Now that the driver is bumped, this guards
 *       against a regression back to that path by asserting Flyway's own bookkeeping table exists and
 *       recorded a successful migration.
 * </ul>
 */
@DisplayName("ClickHouse Analytics Semantics Functional Tests")
public abstract class ClickHouseAnalyticsSemanticsFunctionalTests extends BaseFunctionalTest {

    private static final long CREATED_AT_MS = 1_700_000_000_000L;

    /** A double whose shortest decimal form ({@code 0.8500000000000001}) needs 17 significant digits. */
    private static final double ULP_SENSITIVE_SCORE = 0.8500000000000001d;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricScoreResultRepository metricScoreResultRepository;

    @Autowired
    private TestCaseRunResultRepository testCaseRunResultRepository;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Autowired
    private RunMetricSnapshotRepository runMetricSnapshotRepository;

    @Autowired
    @Qualifier("analyticsDsl")
    private DSLContext analyticsDsl;

    @Test
    @DisplayName("The analytics schema is owned by Flyway, not a hand-rolled initializer: "
            + "flyway_schema_history records a successful V1.1")
    void flywayOwnsTheAnalyticsSchema() {
        Record row =
                analyticsDsl.fetch("select version, success from flyway_schema_history where version = '1.1'").stream()
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No flyway_schema_history row for version 1.1"));

        assertThat(row.get("version", String.class)).isEqualTo("1.1");
        assertThat(row.get("success", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Two inserts sharing an eval-summary natural key are read back as a single row")
    void duplicateNaturalKeyReadsAsOneRow() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();

        // Same (run, computation, test case, run/request/turn index, created_at) key, different row ids —
        // the exact shape a retried batch write produces.
        analyticsTestDataHelper.createEvalSummary(fixture(suiteId, runId, computationId, testCaseId));
        analyticsTestDataHelper.createEvalSummary(fixture(suiteId, runId, computationId, testCaseId));

        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(runId)).hasSize(1);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A 17-significant-digit metric score survives the write/read round trip bit-for-bit")
    void metricScoreRoundTripsExactly() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        metricScoreResultRepository.saveAll(List.of(
                metricScore(runId, computationId, "P90", ULP_SENSITIVE_SCORE),
                metricScore(runId, computationId, "AVG", null)));

        List<MetricScoreResult> persisted = metricScoreResultRepository.findByRunAndComputation(runId, computationId);

        assertThat(persisted).hasSize(2);
        assertThat(persisted)
                .filteredOn(r -> "P90".equals(r.getMetricScoreName()))
                .singleElement()
                .extracting(MetricScoreResult::getValue)
                .isEqualTo(ULP_SENSITIVE_SCORE);
        assertThat(persisted)
                .filteredOn(r -> "AVG".equals(r.getMetricScoreName()))
                .singleElement()
                .extracting(MetricScoreResult::getValue)
                .isNull();
    }

    @Test
    @DisplayName("Escape-worthy characters in JSON payloads and text columns survive the batch write verbatim")
    void escapeWorthyCharactersSurviveBatchWrites() {
        // JSON text whose string values contain a newline, a tab, escaped quotes and a backslash — the
        // characters Jackson escapes with a backslash. ClickHouse interprets backslash escapes in inlined
        // string literals, so any write path that inlines instead of binding corrupts this payload
        // (\n becomes a raw linefeed, \" a bare quote), and the stored column stops being valid JSON.
        final String trickyJson =
                "{\"answer\":\"line1\\nline2\\ttab\",\"quote\":\"say \\\"hi\\\"\",\"win\":\"C:\\\\dir\"}";
        final String trickyJsonArray = "[\"warn line1\\nline2\"]";
        final String trickyText = "back\\slash \"quoted\"\nname";
        final UUID suiteId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID computationId = UUID.randomUUID();

        UUID resultId = UUID.randomUUID();
        testCaseRunResultRepository.saveAll(List.of(TestCaseRunResult.builder()
                .id(resultId)
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName(trickyText)
                .runIndex(0)
                .testCaseData(trickyJson)
                .requestBody(trickyJson)
                .responseBody(trickyJson)
                .executionStatus(ExecutionStatus.SUCCESS)
                .extractedColumns(trickyJson)
                .extractionWarnings(trickyJsonArray)
                .createdAtMs(CREATED_AT_MS)
                .build()));
        TestCaseRunResult persistedResult =
                testCaseRunResultRepository.findById(resultId).orElseThrow();
        assertThat(persistedResult.getExtractedColumns()).isEqualTo(trickyJson);
        assertThat(persistedResult.getTestCaseData()).isEqualTo(trickyJson);
        assertThat(persistedResult.getResponseBody()).isEqualTo(trickyJson);
        assertThat(persistedResult.getExtractionWarnings()).isEqualTo(trickyJsonArray);
        assertThat(persistedResult.getTestCaseName()).isEqualTo(trickyText);

        UUID summaryId = UUID.randomUUID();
        evalSummaryRepository.saveAll(List.of(EvalSummary.builder()
                .id(summaryId)
                .testSuiteId(suiteId)
                .testSuiteRunId(runId)
                .testCaseRunResultId(resultId)
                .testCaseId(UUID.randomUUID())
                .testCaseName(trickyText)
                .runIndex(0)
                .computationId(computationId)
                .testCaseData(trickyJson)
                .extractedColumns(trickyJson)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(100L)
                .metricValues(trickyJson)
                .metricInfos(trickyJson)
                .extractionWarnings(trickyJsonArray)
                .createdAtMs(CREATED_AT_MS)
                .computedAtMs(CREATED_AT_MS)
                .build()));
        EvalSummary persistedSummary = evalSummaryRepository.findById(summaryId).orElseThrow();
        assertThat(persistedSummary.getMetricValues()).isEqualTo(trickyJson);
        assertThat(persistedSummary.getMetricInfos()).isEqualTo(trickyJson);
        assertThat(persistedSummary.getExtractedColumns()).isEqualTo(trickyJson);
        assertThat(persistedSummary.getExtractionWarnings()).isEqualTo(trickyJsonArray);
        assertThat(persistedSummary.getTestCaseName()).isEqualTo(trickyText);

        runMetricSnapshotRepository.saveAll(List.of(RunMetricSnapshot.builder()
                .id(UUID.randomUUID())
                .computationId(computationId)
                .testSuiteRunId(runId)
                .tsmdId(UUID.randomUUID())
                .tsmdName(trickyText)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(trickyJsonArray)
                .inputBindings(trickyJsonArray)
                .outputSchema(trickyJson)
                .computedAtMs(CREATED_AT_MS)
                .build()));
        assertThat(runMetricSnapshotRepository.findByRunIdAndComputationId(runId, computationId))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getConfigBindings()).isEqualTo(trickyJsonArray);
                    assertThat(s.getOutputSchema()).isEqualTo(trickyJson);
                    assertThat(s.getTsmdName()).isEqualTo(trickyText);
                });

        metricScoreResultRepository.saveAll(List.of(metricScore(runId, computationId, trickyText, 0.5d)));
        assertThat(metricScoreResultRepository.findByRunAndComputation(runId, computationId))
                .singleElement()
                .extracting(MetricScoreResult::getMetricScoreName)
                .isEqualTo(trickyText);
    }

    private static EvalSummaryFixture fixture(UUID suiteId, UUID runId, UUID computationId, UUID testCaseId) {
        return EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("duplicated-case")
                .executionStatus(ExecutionStatus.SUCCESS.name())
                .execDurationMs(100L)
                .createdAtMs(CREATED_AT_MS)
                .testCaseDataJson("{}")
                .metricValuesJson("{}")
                .build();
    }

    private static MetricScoreResult metricScore(UUID runId, UUID computationId, String scoreName, Double value) {
        return MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testSuiteRunId(runId)
                .computationId(computationId)
                .metricScoreName(scoreName)
                .metricName("Relevancy.score")
                .value(value)
                .computedAtMs(CREATED_AT_MS)
                .build();
    }
}
