package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.datasource.ClickHouseAnalyticsBackfillMigration;
import com.epam.aidial.evaluation.configuration.properties.clickhouse.ClickHouseBackfillProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.functional.ClickHouseFunctionalTests;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.BackfillSourcePostgresDatabase;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * End-to-end coverage of {@link ClickHouseAnalyticsBackfillMigration} against real engines on both
 * sides: a scratch Postgres database carrying the production analytics POSTGRES schema (see
 * {@link BackfillSourcePostgresDatabase}) and the suite's ClickHouse analytics datasource. The copy
 * itself runs server-side in ClickHouse via its {@code postgresql()} table function, so this also
 * proves the container-to-container connectivity path the migration depends on.
 *
 * <p>JSON payload assertions are semantic ({@link ObjectMapper#readTree}), not byte-exact: the source
 * columns are Postgres JSONB, which canonicalizes key order and whitespace at rest — the backfill
 * faithfully copies what Postgres serves, which is already the canonicalized form. Plain VARCHAR
 * columns and the Float64 metric score are asserted exactly.
 */
@DisplayName("ClickHouse Backfill Migration Functional Tests")
public abstract class ClickHouseBackfillFunctionalTests extends BaseFunctionalTest {

    private static final long CREATED_AT_MS = 1_700_000_000_000L;

    /** A double whose shortest decimal form needs 17 significant digits — catches lossy transfer. */
    private static final double ULP_SENSITIVE_SCORE = 0.8500000000000001d;

    private static final String TRICKY_TEXT = "back\\slash \"quoted\"\nname";
    private static final String TRICKY_JSON =
            "{\"answer\":\"line1\\nline2\\ttab\",\"quote\":\"say \\\"hi\\\"\",\"win\":\"C:\\\\dir\"}";
    private static final String TRICKY_JSON_ARRAY = "[\"warn line1\\nline2\"]";

    @Autowired
    @Qualifier("analyticsDataSource")
    private DataSource analyticsDataSource;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseRunResultRepository testCaseRunResultRepository;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Autowired
    private RunMetricSnapshotRepository runMetricSnapshotRepository;

    @Autowired
    private MetricScoreResultRepository metricScoreResultRepository;

    /** Local instance: only used for {@link ObjectMapper#readTree} equality, which is config-independent. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Backfill copies all four tables from the Postgres analytics schema, "
            + "preserves escape-worthy payloads and exact doubles, and re-runs idempotently")
    void backfillCopiesAllFourTablesAndRerunsIdempotently() throws Exception {
        BackfillSourcePostgresDatabase source = BackfillSourcePostgresDatabase.recreate(
                ClickHouseFunctionalTests.getMetaContainer(), "backfill_source");
        BackfillSourcePostgresDatabase.Fixture fixture = new BackfillSourcePostgresDatabase.Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                TRICKY_TEXT,
                TRICKY_JSON,
                TRICKY_JSON_ARRAY,
                "P90",
                ULP_SENSITIVE_SCORE,
                CREATED_AT_MS);
        source.insertFixtureRows(fixture);

        ClickHouseAnalyticsBackfillMigration migration =
                new ClickHouseAnalyticsBackfillMigration(backfillProperties(source));
        runAgainstAnalyticsDatasource(migration);

        assertRunResultCopiedVerbatim(fixture);
        assertEvalSummaryCopiedVerbatim(fixture);
        assertSnapshotAndScoreCopiedVerbatim(fixture);

        runAgainstAnalyticsDatasource(migration);
        assertThat(analyticsTestDataHelper.countAll()).isEqualTo(1);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(1);
        assertThat(analyticsTestDataHelper.countRunMetricSnapshots()).isEqualTo(1);
        assertThat(metricScoreResultRepository.findByRunAndComputation(fixture.runId(), fixture.computationId()))
                .hasSize(1);
    }

    private void assertRunResultCopiedVerbatim(BackfillSourcePostgresDatabase.Fixture fixture) throws Exception {
        TestCaseRunResult result =
                testCaseRunResultRepository.findById(fixture.resultId()).orElseThrow();
        assertThat(result.getTestCaseName()).isEqualTo(TRICKY_TEXT);
        assertJsonEquals(result.getTestCaseData(), TRICKY_JSON);
        assertJsonEquals(result.getResponseBody(), TRICKY_JSON);
        assertJsonEquals(result.getExtractedColumns(), TRICKY_JSON);
        assertJsonEquals(result.getExtractionWarnings(), TRICKY_JSON_ARRAY);
        assertThat(result.getCreatedAtMs()).isEqualTo(CREATED_AT_MS);
    }

    private void assertEvalSummaryCopiedVerbatim(BackfillSourcePostgresDatabase.Fixture fixture) throws Exception {
        EvalSummary summary =
                evalSummaryRepository.findById(fixture.summaryId()).orElseThrow();
        assertThat(summary.getTestCaseName()).isEqualTo(TRICKY_TEXT);
        assertThat(summary.getComputationId()).isEqualTo(fixture.computationId());
        assertJsonEquals(summary.getMetricValues(), TRICKY_JSON);
        assertJsonEquals(summary.getMetricInfos(), TRICKY_JSON);
        assertJsonEquals(summary.getExtractedColumns(), TRICKY_JSON);
        assertJsonEquals(summary.getExtractionWarnings(), TRICKY_JSON_ARRAY);
    }

    private void assertSnapshotAndScoreCopiedVerbatim(BackfillSourcePostgresDatabase.Fixture fixture) throws Exception {
        var snapshots =
                runMetricSnapshotRepository.findByRunIdAndComputationId(fixture.runId(), fixture.computationId());
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().getTsmdName()).isEqualTo(TRICKY_TEXT);
        assertJsonEquals(snapshots.getFirst().getConfigBindings(), TRICKY_JSON_ARRAY);
        assertJsonEquals(snapshots.getFirst().getOutputSchema(), TRICKY_JSON);

        assertThat(metricScoreResultRepository.findByRunAndComputation(fixture.runId(), fixture.computationId()))
                .singleElement()
                .satisfies(score -> {
                    assertThat(score.getMetricScoreName()).isEqualTo("P90");
                    assertThat(score.getValue()).isEqualTo(ULP_SENSITIVE_SCORE);
                })
                .extracting(MetricScoreResult::getTestSuiteId)
                .isEqualTo(fixture.suiteId());
    }

    private ClickHouseBackfillProperties backfillProperties(BackfillSourcePostgresDatabase source) {
        ClickHouseBackfillProperties.Postgres postgres = new ClickHouseBackfillProperties.Postgres();
        postgres.setHost(source.hostReachableFromClickHouse());
        postgres.setPort(source.port());
        postgres.setDatabase(source.databaseName());
        postgres.setSchema("public");
        postgres.setUsername(source.username());
        postgres.setPassword(source.password());
        ClickHouseBackfillProperties properties = new ClickHouseBackfillProperties();
        properties.setEnabled(true);
        properties.setPostgres(postgres);
        return properties;
    }

    private void runAgainstAnalyticsDatasource(ClickHouseAnalyticsBackfillMigration migration) throws Exception {
        try (Connection connection = analyticsDataSource.getConnection()) {
            migration.migrate(new Context() {
                @Override
                public Configuration getConfiguration() {
                    return null;
                }

                @Override
                public Connection getConnection() {
                    return connection;
                }
            });
        }
    }

    private void assertJsonEquals(String actualJson, String expectedJson) throws Exception {
        assertThat(objectMapper.readTree(actualJson)).isEqualTo(objectMapper.readTree(expectedJson));
    }
}
