package com.epam.aidial.evaluation.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.jooq.analytics.Tables;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * ClickHouse-side counterpart of {@link JooqSchemaDriftTest}.
 *
 * <p>The generated analytics model is produced from the CLICKHOUSE migrations by {@code ./gradlew
 * generateClickHouseJooq}, so ClickHouse is the source of truth for that model. This test catches
 * the "edited a CLICKHOUSE migration but forgot to rerun the codegen task" case: it migrates a live
 * ClickHouse instance and compares its {@code system.columns} against the committed generated table
 * classes in both directions — no missing columns, no extra columns — plus nullability and the
 * ClickHouse-type → jOOQ-type mapping the codegen's forced types are expected to produce.
 *
 * <p>This is a plain JUnit test: it needs Docker but no Spring context.
 */
class ClickHouseSchemaDriftTest {

    private static final String CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:25.8";
    private static final String DATABASE = "evaluation_analytics";
    private static final String USER = "clickhouse";
    private static final String PASSWORD = "clickhouse";
    private static final int HTTP_PORT = 8123;

    /**
     * Columns forced to {@code JSONB} by the codegen. ClickHouse stores them as {@code String} (the
     * native JSON type is deliberately avoided — see the CLICKHOUSE migration header), so the model's
     * {@code Field<JSONB>} surface exists only because of the forced type.
     */
    private static final Set<String> JSONB_COLUMNS = Set.of(
            "test_case_run_results.test_case_data",
            "test_case_run_results.request_body",
            "test_case_run_results.response_body",
            "test_case_run_results.log_details",
            "test_case_run_results.extracted_columns",
            "test_case_run_results.extraction_warnings",
            "test_case_eval_summaries.test_case_data",
            "test_case_eval_summaries.extracted_columns",
            "test_case_eval_summaries.metric_values",
            "test_case_eval_summaries.metric_infos",
            "test_case_eval_summaries.extraction_warnings",
            "run_metric_snapshots.config_bindings",
            "run_metric_snapshots.input_bindings",
            "run_metric_snapshots.output_schema");

    /**
     * Columns forced to {@code VARCHAR(36)} by the codegen. The length is load-bearing: {@code
     * JooqTableSchemaResolver} infers the {@code uuid} query-schema type from {@code length() == 36}.
     */
    private static final Set<String> UUID_COLUMNS = Set.of(
            "test_case_run_results.id",
            "test_case_run_results.test_suite_run_id",
            "test_case_run_results.test_suite_id",
            "test_case_run_results.test_case_id",
            "test_case_eval_summaries.id",
            "test_case_eval_summaries.test_suite_id",
            "test_case_eval_summaries.test_suite_run_id",
            "test_case_eval_summaries.test_case_run_result_id",
            "test_case_eval_summaries.test_case_id",
            "test_case_eval_summaries.computation_id",
            "run_metric_snapshots.id",
            "run_metric_snapshots.computation_id",
            "run_metric_snapshots.test_suite_run_id",
            "run_metric_snapshots.tsmd_id",
            "run_metric_snapshots.metric_declaration_id",
            "run_metric_snapshots.metric_declaration_version_id",
            "metric_score_result.id",
            "metric_score_result.test_suite_run_id",
            "metric_score_result.computation_id",
            "metric_score_result.test_suite_id");

    private static final int UUID_COLUMN_LENGTH = 36;

    /** ClickHouse base type (after unwrapping {@code Nullable}/{@code LowCardinality}) → jOOQ type. */
    private static final Map<String, DataType<?>> TYPE_MAPPING = Map.of(
            "String", SQLDataType.VARCHAR,
            "Int64", SQLDataType.BIGINT,
            "Int32", SQLDataType.INTEGER,
            "Float64", SQLDataType.DOUBLE);

    private static final String REGENERATE_HINT =
            "Run './gradlew generateClickHouseJooq' to regenerate the analytics sources.";

    private static GenericContainer<?> clickhouse;
    private static String jdbcUrl;

    @BeforeAll
    static void startClickHouse() {
        clickhouse = new GenericContainer<>(CLICKHOUSE_IMAGE)
                .withEnv("CLICKHOUSE_DB", DATABASE)
                .withEnv("CLICKHOUSE_USER", USER)
                .withEnv("CLICKHOUSE_PASSWORD", PASSWORD)
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3));
        clickhouse.start();

        // flyway-database-clickhouse only recognises the "jdbc:clickhouse:" prefix, not the driver's
        // shorter "jdbc:ch:" alias (see AnalyticsClickHouseConfiguration).
        jdbcUrl = "jdbc:clickhouse://%s:%d/%s"
                .formatted(clickhouse.getHost(), clickhouse.getMappedPort(HTTP_PORT), DATABASE);

        Flyway.configure()
                .dataSource(jdbcUrl, USER, PASSWORD)
                .locations("classpath:db/migration/analytics/CLICKHOUSE")
                .defaultSchema(DATABASE)
                .createSchemas(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }

    @AfterAll
    static void stopClickHouse() {
        if (clickhouse != null) {
            clickhouse.stop();
        }
    }

    @Test
    @DisplayName("every generated analytics table matches the live ClickHouse schema column-for-column")
    void generatedAnalyticsTablesMatchLiveClickHouseColumns() throws SQLException {
        for (final Table<?> table : analyticsTables()) {
            final Map<String, String> liveColumns = liveColumns(table.getName());
            final Set<String> generatedColumns =
                    Arrays.stream(table.fields()).map(Field::getName).collect(Collectors.toSet());

            assertThat(generatedColumns)
                    .as(
                            "Schema drift in '%s': generated columns differ from the live ClickHouse table. %s",
                            table.getName(), REGENERATE_HINT)
                    .containsExactlyInAnyOrderElementsOf(liveColumns.keySet());
        }
    }

    @Test
    @DisplayName("every generated analytics column has the jOOQ type and nullability its ClickHouse type implies")
    void generatedAnalyticsColumnTypesMatchClickHouseTypes() throws SQLException {
        for (final Table<?> table : analyticsTables()) {
            final Map<String, String> liveColumns = liveColumns(table.getName());

            for (final Field<?> field : table.fields()) {
                final String qualified = table.getName() + "." + field.getName();
                final String clickHouseType = liveColumns.get(field.getName());
                final DataType<?> dataType = field.getDataType();

                assertThat(dataType.nullable())
                        .as(
                                "Nullability drift on '%s': ClickHouse declares '%s'. %s",
                                qualified, clickHouseType, REGENERATE_HINT)
                        .isEqualTo(isNullable(clickHouseType));

                assertThat(dataType.getSQLDataType())
                        .as(
                                "Type drift on '%s': ClickHouse declares '%s'. %s",
                                qualified, clickHouseType, REGENERATE_HINT)
                        .isEqualTo(expectedType(qualified, clickHouseType));

                if (UUID_COLUMNS.contains(qualified)) {
                    assertThat(dataType.length())
                            .as(
                                    "'%s' must stay VARCHAR(%d) — the query schema infers 'uuid' from that length. %s",
                                    qualified, UUID_COLUMN_LENGTH, REGENERATE_HINT)
                            .isEqualTo(UUID_COLUMN_LENGTH);
                }
            }
        }
    }

    private static List<Table<?>> analyticsTables() {
        return List.of(
                Tables.TEST_CASE_RUN_RESULTS,
                Tables.TEST_CASE_EVAL_SUMMARIES,
                Tables.RUN_METRIC_SNAPSHOTS,
                Tables.METRIC_SCORE_RESULT);
    }

    /** Live column name → declared ClickHouse type, for one table. */
    private static Map<String, String> liveColumns(String tableName) throws SQLException {
        final Map<String, String> columns = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT name, type FROM system.columns WHERE database = ? AND table = ? ORDER BY position")) {
            statement.setString(1, DATABASE);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.put(resultSet.getString("name"), resultSet.getString("type"));
                }
            }
        }
        assertThat(columns)
                .as(
                        "Table '%s' is missing from the live ClickHouse schema; add it to the CLICKHOUSE migrations "
                                + "or drop it from the generated model. %s",
                        tableName, REGENERATE_HINT)
                .isNotEmpty();
        return columns;
    }

    private static boolean isNullable(String clickHouseType) {
        return clickHouseType.startsWith("Nullable(");
    }

    /**
     * The jOOQ type the codegen is expected to produce for a ClickHouse column: {@code JSONB} for the
     * forced JSON payload columns, otherwise the base type's mapping. {@code Nullable(...)} and
     * {@code LowCardinality(...)} wrappers are transparent.
     */
    private static DataType<?> expectedType(String qualifiedColumn, String clickHouseType) {
        if (JSONB_COLUMNS.contains(qualifiedColumn)) {
            return SQLDataType.JSONB;
        }
        final String baseType = unwrap(clickHouseType);
        final DataType<?> mapped = TYPE_MAPPING.get(baseType);
        assertThat(mapped)
                .as(
                        "No jOOQ type mapping for ClickHouse type '%s' on '%s'; extend TYPE_MAPPING in this test "
                                + "and the forced types in the generateClickHouseJooq task.",
                        clickHouseType, qualifiedColumn)
                .isNotNull();
        return mapped;
    }

    /** Strips the {@code Nullable(...)} and {@code LowCardinality(...)} wrappers, innermost last. */
    private static String unwrap(String clickHouseType) {
        String type = clickHouseType;
        boolean unwrapped = true;
        while (unwrapped) {
            unwrapped = false;
            for (final String wrapper : List.of("Nullable(", "LowCardinality(")) {
                if (type.startsWith(wrapper) && type.endsWith(")")) {
                    type = type.substring(wrapper.length(), type.length() - 1);
                    unwrapped = true;
                }
            }
        }
        return type;
    }
}
