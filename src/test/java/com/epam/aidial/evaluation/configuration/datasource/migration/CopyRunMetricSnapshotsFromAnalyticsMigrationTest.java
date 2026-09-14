package com.epam.aidial.evaluation.configuration.datasource.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.epam.aidial.evaluation.data.db.jooq.meta.Tables;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Drives {@link V1_33__CopyRunMetricSnapshotsFromAnalytics} directly against two Testcontainers
 * Postgres datasources. The copy cannot be exercised through a normal application boot: Flyway
 * runs during bean construction, long before any functional-test fixture exists, so the analytics
 * source would always be empty (see the design's Migration Plan / Testing approach section).
 */
@DisplayName("V1_33__CopyRunMetricSnapshotsFromAnalytics")
class CopyRunMetricSnapshotsFromAnalyticsMigrationTest {

    private static final String PUBLIC_SCHEMA = "public";
    private static final String EMPTY_SCHEMA = "empty_source_schema";
    private static final String ROLLBACK_META_SCHEMA = "rollback_meta";
    private static final String ROLLBACK_ANALYTICS_SCHEMA = "rollback_analytics";
    private static final String FORCE_FAILURE_TSMD_NAME = "FORCE_FAILURE";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static PostgreSQLContainer metaPostgres;
    private static PostgreSQLContainer analyticsPostgres;

    private DataSource metaDataSource;
    private DataSource analyticsDataSource;

    @BeforeAll
    static void startContainersAndApplyBaselineMigrations() throws SQLException {
        metaPostgres = new PostgreSQLContainer("postgres:17.4");
        metaPostgres.start();
        analyticsPostgres = new PostgreSQLContainer("postgres:17.4");
        analyticsPostgres.start();

        applyMigrations(dataSourceFor(metaPostgres, PUBLIC_SCHEMA), "db/migration/meta/POSTGRES", PUBLIC_SCHEMA);
        applyMigrations(
                dataSourceFor(analyticsPostgres, PUBLIC_SCHEMA), "db/migration/analytics/POSTGRES", PUBLIC_SCHEMA);

        createSchema(analyticsPostgres, EMPTY_SCHEMA);

        applyMigrations(
                dataSourceFor(metaPostgres, ROLLBACK_META_SCHEMA), "db/migration/meta/POSTGRES", ROLLBACK_META_SCHEMA);
        applyMigrations(
                dataSourceFor(analyticsPostgres, ROLLBACK_ANALYTICS_SCHEMA),
                "db/migration/analytics/POSTGRES",
                ROLLBACK_ANALYTICS_SCHEMA);
        addForceFailureConstraint(dataSourceFor(metaPostgres, ROLLBACK_META_SCHEMA));
    }

    @AfterAll
    static void stopContainers() {
        if (analyticsPostgres != null) {
            analyticsPostgres.stop();
        }
        if (metaPostgres != null) {
            metaPostgres.stop();
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        metaDataSource = dataSourceFor(metaPostgres, PUBLIC_SCHEMA);
        analyticsDataSource = dataSourceFor(analyticsPostgres, PUBLIC_SCHEMA);
        cleanPublicSchemaTables();
    }

    @Test
    @DisplayName("copies analytics rows into meta with identical scalar and JSONB column values")
    void shouldCopyAllColumns_whenSourceRowsExist() throws SQLException {
        DSLContext metaDsl = metaDsl(PUBLIC_SCHEMA);
        String runId = createRun(metaDsl);

        SnapshotFixture first =
                snapshotFor(runId, "[{\"binding\":\"a\"}]", "[{\"binding\":\"b\"}]", "{\"type\":\"number\"}");
        SnapshotFixture second = snapshotFor(runId, "[]", "[]", "{}");
        insertAnalyticsSnapshot(analyticsDataSource, first);
        insertAnalyticsSnapshot(analyticsDataSource, second);

        runMigration(analyticsDataSource, PUBLIC_SCHEMA);

        assertSnapshotPersisted(metaDsl, first);
        assertSnapshotPersisted(metaDsl, second);
    }

    @Test
    @DisplayName("re-running against an already-populated target inserts nothing and does not fail")
    void shouldInsertNothingAndNotFail_whenTargetAlreadyPopulated() throws SQLException {
        DSLContext metaDsl = metaDsl(PUBLIC_SCHEMA);
        String runId = createRun(metaDsl);
        SnapshotFixture fixture = snapshotFor(runId, "[]", "[]", "{}");
        insertAnalyticsSnapshot(analyticsDataSource, fixture);

        runMigration(analyticsDataSource, PUBLIC_SCHEMA);
        assertThat(metaDsl.fetchCount(Tables.RUN_METRIC_SNAPSHOTS)).isEqualTo(1);

        runMigration(analyticsDataSource, PUBLIC_SCHEMA);

        assertThat(metaDsl.fetchCount(Tables.RUN_METRIC_SNAPSHOTS)).isEqualTo(1);
        assertSnapshotPersisted(metaDsl, fixture);
    }

    @Test
    @DisplayName("skips a row whose run is absent from meta, logs the dropped count, and completes successfully")
    void shouldSkipOrphanRowAndCompleteSuccessfully_whenRunMissingFromMeta() throws SQLException {
        DSLContext metaDsl = metaDsl(PUBLIC_SCHEMA);
        String validRunId = createRun(metaDsl);
        String orphanRunId = UUID.randomUUID().toString();

        SnapshotFixture validRow = snapshotFor(validRunId, "[]", "[]", "{}");
        SnapshotFixture orphanRow = snapshotFor(orphanRunId, "[]", "[]", "{}");
        insertAnalyticsSnapshot(analyticsDataSource, validRow);
        insertAnalyticsSnapshot(analyticsDataSource, orphanRow);

        List<LogEvent> events = captureLogsDuring(() -> runMigration(analyticsDataSource, PUBLIC_SCHEMA));

        assertThat(metaDsl.fetchCount(Tables.RUN_METRIC_SNAPSHOTS)).isEqualTo(1);
        assertSnapshotPersisted(metaDsl, validRow);
        assertThat(metaDsl.fetchCount(Tables.RUN_METRIC_SNAPSHOTS, Tables.RUN_METRIC_SNAPSHOTS.ID.eq(orphanRow.id())))
                .isZero();
        assertThat(events)
                .anySatisfy(event ->
                        assertThat(event.getMessage().getFormattedMessage()).contains("1 dropped as orphans"));
    }

    @Test
    @DisplayName("skips the copy and completes successfully when the analytics source table does not exist")
    void shouldSkipAndCompleteSuccessfully_whenAnalyticsSourceTableDoesNotExist() throws SQLException {
        DSLContext metaDsl = metaDsl(PUBLIC_SCHEMA);
        DataSource emptySchemaDataSource = dataSourceFor(analyticsPostgres, EMPTY_SCHEMA);

        List<LogEvent> events = captureLogsDuring(() -> runMigration(emptySchemaDataSource, EMPTY_SCHEMA));

        assertThat(metaDsl.fetchCount(Tables.RUN_METRIC_SNAPSHOTS)).isZero();
        assertThat(events)
                .anySatisfy(event ->
                        assertThat(event.getMessage().getFormattedMessage()).contains("source table does not exist"));
    }

    // Wraps the migration logger's level/appender mutation and its restoration in one try/finally
    // so state is never left mutated: the whole setup (start the appender, raise the level, attach
    // it) runs inside the try, so a failure partway through still triggers cleanup of whatever was
    // already applied, and both the setup and the assertions in each caller happen outside this
    // method's try/finally, so a failing assertion can never skip restoration. Scoped to the
    // migration's own logger, never root, so it cannot race with unrelated loggers.
    private List<LogEvent> captureLogsDuring(ThrowingAction action) throws SQLException {
        Logger logger = (Logger) LogManager.getLogger(V1_33__CopyRunMetricSnapshotsFromAnalytics.class);
        Level originalLevel = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        try {
            appender.start();
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
        return appender.events();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws SQLException;
    }

    @Test
    @DisplayName("rolls back both the copied rows and the migration's own history entry when a row fails mid-copy")
    void shouldRollBackDataAndHistory_whenCopyFailsMidway() throws SQLException {
        DataSource rollbackMetaDataSource = dataSourceFor(metaPostgres, ROLLBACK_META_SCHEMA);
        DataSource rollbackAnalyticsDataSource = dataSourceFor(analyticsPostgres, ROLLBACK_ANALYTICS_SCHEMA);
        DSLContext rollbackMetaDsl = metaDsl(ROLLBACK_META_SCHEMA);
        String runId = createRun(rollbackMetaDsl);

        insertAnalyticsSnapshot(rollbackAnalyticsDataSource, snapshotFor(runId, "[]", "[]", "{}"));
        insertAnalyticsSnapshot(
                rollbackAnalyticsDataSource,
                new SnapshotFixture(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        runId,
                        UUID.randomUUID().toString(),
                        FORCE_FAILURE_TSMD_NAME,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "[]",
                        "[]",
                        "{}",
                        1L));

        Flyway flyway = Flyway.configure()
                .dataSource(rollbackMetaDataSource)
                .locations("classpath:db/migration/meta/POSTGRES")
                .schemas(ROLLBACK_META_SCHEMA)
                .javaMigrations(new V1_33__CopyRunMetricSnapshotsFromAnalytics(
                        rollbackAnalyticsDataSource, ROLLBACK_ANALYTICS_SCHEMA))
                .load();

        assertThrows(FlywayException.class, flyway::migrate);

        assertThat(rollbackMetaDsl.fetchCount(Tables.RUN_METRIC_SNAPSHOTS)).isZero();
        assertThat(countFlywayHistoryEntriesForVersion(rollbackMetaDataSource, "1.33"))
                .isZero();
    }

    private void assertSnapshotPersisted(DSLContext metaDsl, SnapshotFixture fixture) {
        var record = metaDsl.selectFrom(Tables.RUN_METRIC_SNAPSHOTS)
                .where(Tables.RUN_METRIC_SNAPSHOTS.ID.eq(fixture.id()))
                .fetchOne();

        assertThat(record).isNotNull();
        assertThat(record.getComputationId()).isEqualTo(fixture.computationId());
        assertThat(record.getTestSuiteRunId()).isEqualTo(fixture.testSuiteRunId());
        assertThat(record.getTsmdId()).isEqualTo(fixture.tsmdId());
        assertThat(record.getTsmdName()).isEqualTo(fixture.tsmdName());
        assertThat(record.getMetricDeclarationId()).isEqualTo(fixture.metricDeclarationId());
        assertThat(record.getMetricDeclarationVersionId()).isEqualTo(fixture.metricDeclarationVersionId());
        assertJsonEquals(record.getConfigBindings().data(), fixture.configBindings());
        assertJsonEquals(record.getInputBindings().data(), fixture.inputBindings());
        assertJsonEquals(record.getOutputSchema().data(), fixture.outputSchema());
        assertThat(record.getComputedAtMs()).isEqualTo(fixture.computedAtMs());
    }

    // Postgres reformats jsonb on write (e.g. inserting a space after ":"), so the round-tripped
    // text is not byte-for-byte identical to what was written even though the value is unchanged.
    // Comparing parsed trees rather than raw strings verifies "identical JSONB column values"
    // without depending on Postgres's specific jsonb pretty-printing.
    private void assertJsonEquals(String actualJson, String expectedJson) {
        try {
            assertThat(JSON_MAPPER.readTree(actualJson)).isEqualTo(JSON_MAPPER.readTree(expectedJson));
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to parse JSON for comparison: " + actualJson, e);
        }
    }

    private void cleanPublicSchemaTables() throws SQLException {
        DSLContext metaDsl = metaDsl(PUBLIC_SCHEMA);
        metaDsl.deleteFrom(Tables.RUN_METRIC_SNAPSHOTS).execute();
        metaDsl.deleteFrom(Tables.TEST_SUITE_RUNS).execute();
        metaDsl.deleteFrom(Tables.TEST_SUITES).execute();

        try (Connection connection = analyticsDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM run_metric_snapshots")) {
            statement.executeUpdate();
        }
    }

    private static void runMigration(DataSource analyticsDs, String analyticsSchema) throws SQLException {
        V1_33__CopyRunMetricSnapshotsFromAnalytics migration =
                new V1_33__CopyRunMetricSnapshotsFromAnalytics(analyticsDs, analyticsSchema);
        DataSource metaDs = dataSourceFor(metaPostgres, PUBLIC_SCHEMA);
        try (Connection metaConnection = metaDs.getConnection()) {
            metaConnection.setAutoCommit(false);
            migration.migrate(contextFor(metaConnection));
            metaConnection.commit();
        }
    }

    private static Context contextFor(Connection connection) {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                throw new UnsupportedOperationException("not used by this migration");
            }

            @Override
            public Connection getConnection() {
                return connection;
            }
        };
    }

    // The generated jOOQ meta Tables carry the codegen-time schema name "meta" (build.gradle's
    // generateJooq task, an artifact of how it distinguishes the meta/analytics schemas while
    // generating from one embedded Postgres instance). Production's own metaDsl bean renders
    // schema-less SQL for the same reason (MetaJdbcConfiguration: `Settings().withRenderSchema
    // (false)`), relying on the connection's search_path — set here via
    // PGSimpleDataSource#setCurrentSchema — to resolve the unqualified table name.
    private static DSLContext metaDsl(String schema) {
        return DSL.using(
                dataSourceFor(metaPostgres, schema), SQLDialect.POSTGRES, new Settings().withRenderSchema(false));
    }

    private static String createRun(DSLContext metaDsl) {
        String testSuiteId = UUID.randomUUID().toString();
        metaDsl.insertInto(Tables.TEST_SUITES)
                .set(Tables.TEST_SUITES.ID, testSuiteId)
                .set(Tables.TEST_SUITES.NAME, "suite-" + testSuiteId)
                .set(Tables.TEST_SUITES.CREATED_BY, "test")
                .set(Tables.TEST_SUITES.CREATED_AT_MS, 1L)
                .set(Tables.TEST_SUITES.UPDATED_AT_MS, 1L)
                .execute();

        String testSuiteRunId = UUID.randomUUID().toString();
        metaDsl.insertInto(Tables.TEST_SUITE_RUNS)
                .set(Tables.TEST_SUITE_RUNS.ID, testSuiteRunId)
                .set(Tables.TEST_SUITE_RUNS.TEST_SUITE_ID, testSuiteId)
                .set(Tables.TEST_SUITE_RUNS.TEST_RUN_NAME, "run-" + testSuiteRunId)
                .set(Tables.TEST_SUITE_RUNS.STATUS, "COMPLETED")
                .set(Tables.TEST_SUITE_RUNS.RUN_CONFIG, JSONB.valueOf("{}"))
                .set(Tables.TEST_SUITE_RUNS.NUMBER_OF_TEST_CASES, 0)
                .set(Tables.TEST_SUITE_RUNS.CREATED_AT_MS, 1L)
                .set(Tables.TEST_SUITE_RUNS.UPDATED_AT_MS, 1L)
                .execute();
        return testSuiteRunId;
    }

    private static SnapshotFixture snapshotFor(
            String runId, String configBindings, String inputBindings, String outputSchema) {
        return new SnapshotFixture(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                runId,
                UUID.randomUUID().toString(),
                "accuracy",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                configBindings,
                inputBindings,
                outputSchema,
                1_700_000_000_000L);
    }

    private static void insertAnalyticsSnapshot(DataSource analyticsDs, SnapshotFixture fixture) throws SQLException {
        String sql = """
                INSERT INTO run_metric_snapshots (id, computation_id, test_suite_run_id, tsmd_id, tsmd_name,
                    metric_declaration_id, metric_declaration_version_id, config_bindings, input_bindings,
                    output_schema, computed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """;
        try (Connection connection = analyticsDs.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fixture.id());
            statement.setString(2, fixture.computationId());
            statement.setString(3, fixture.testSuiteRunId());
            statement.setString(4, fixture.tsmdId());
            statement.setString(5, fixture.tsmdName());
            statement.setString(6, fixture.metricDeclarationId());
            statement.setString(7, fixture.metricDeclarationVersionId());
            statement.setString(8, fixture.configBindings());
            statement.setString(9, fixture.inputBindings());
            statement.setString(10, fixture.outputSchema());
            statement.setLong(11, fixture.computedAtMs());
            statement.executeUpdate();
        }
    }

    private static int countFlywayHistoryEntriesForVersion(DataSource dataSource, String version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM flyway_schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void addForceFailureConstraint(DataSource metaDs) throws SQLException {
        try (Connection connection = metaDs.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "ALTER TABLE run_metric_snapshots ADD CONSTRAINT ck_test_force_failure "
                                + "CHECK (tsmd_name <> '" + FORCE_FAILURE_TSMD_NAME + "')")) {
            statement.executeUpdate();
        }
    }

    private static void createSchema(PostgreSQLContainer container, String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        container.getJdbcUrl(), container.getUsername(), container.getPassword());
                PreparedStatement statement = connection.prepareStatement("CREATE SCHEMA IF NOT EXISTS " + schema)) {
            statement.executeUpdate();
        }
    }

    private static void applyMigrations(DataSource dataSource, String location, String schema) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:" + location)
                .schemas(schema)
                .load()
                .migrate();
    }

    private static DataSource dataSourceFor(PostgreSQLContainer container, String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setCurrentSchema(schema);
        return dataSource;
    }

    private record SnapshotFixture(
            String id,
            String computationId,
            String testSuiteRunId,
            String tsmdId,
            String tsmdName,
            String metricDeclarationId,
            String metricDeclarationVersionId,
            String configBindings,
            String inputBindings,
            String outputSchema,
            long computedAtMs) {}

    /**
     * Collects the migration logger's events so tests can assert on skip/orphan log narration. The
     * project logs through Log4j2 (see log4j2.xml), so this is a Log4j2 appender rather than a
     * Logback ListAppender.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("capturing", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return events;
        }
    }
}
