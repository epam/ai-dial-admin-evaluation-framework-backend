package com.epam.aidial.evaluation.data.db.jooq;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Analytics.ANALYTICS;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.jooq.analytics.Analytics;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Schema;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the two generated analytics models together. The analytics schema is <b>dual-authored</b>:
 * every table exists twice in {@code src/main/java-generated}, once per vendor, each generated from
 * that vendor's own Flyway migrations —
 *
 * <ul>
 *   <li>{@code ...jooq.analytics} — from {@code db/migration/analytics/POSTGRES} via
 *       {@code ./gradlew generateJooq}; consumed by shared query code, the mappers, the schema
 *       providers and the Postgres repositories.
 *   <li>{@code ...jooq.clickhouse} — from {@code db/migration/analytics/CLICKHOUSE} via
 *       {@code ./gradlew generateClickHouseJooq}; consumed by the ClickHouse repositories and entity
 *       resolvers.
 * </ul>
 *
 * <p>The two models must describe the same logical table, because a single API surface (the query
 * schema endpoint, the filter whitelists, the record mappers) is served from whichever one is in
 * play. This test is what makes that a guarantee rather than a hope: it compares the two committed
 * models column-for-column, so a change applied to one vendor's migration but not the other's fails
 * here rather than at runtime on one deployment only.
 *
 * <p>This is a plain unit test — no Docker, no database, no Spring context. The live-schema guards
 * are separate: {@code JooqSchemaDriftTest} (Postgres) and {@code ClickHouseSchemaDriftTest}.
 */
class AnalyticsModelParityTest {

    private static final Schema POSTGRES_MODEL = Analytics.ANALYTICS;
    private static final Schema CLICKHOUSE_MODEL = ANALYTICS;

    private static final String WORKFLOW_HINT = """
            The analytics schema is dual-authored: change the CLICKHOUSE migration AND its POSTGRES \
            twin under src/main/resources/db/migration/analytics, then rerun BOTH \
            './gradlew generateClickHouseJooq' and './gradlew generateJooq' and commit the regenerated \
            sources.""";

    private static final JooqTableSchemaResolver SCHEMA_RESOLVER = new JooqTableSchemaResolver();

    static Stream<String> tableNames() {
        return POSTGRES_MODEL.getTables().stream().map(Table::getName).sorted();
    }

    @Test
    @DisplayName("both generated analytics models declare exactly the same set of tables")
    void modelsDeclareTheSameTables() {
        assertThat(tableNamesOf(CLICKHOUSE_MODEL))
                .as("Table drift between the two generated analytics models. %s", WORKFLOW_HINT)
                .containsExactlyInAnyOrderElementsOf(tableNamesOf(POSTGRES_MODEL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableNames")
    @DisplayName("both generated analytics models declare exactly the same columns for a table")
    void modelsDeclareTheSameColumns(String tableName) {
        assertThat(columnsOf(CLICKHOUSE_MODEL, tableName).keySet())
                .as("Column drift in '%s' between the two generated analytics models. %s", tableName, WORKFLOW_HINT)
                .containsExactlyInAnyOrderElementsOf(
                        columnsOf(POSTGRES_MODEL, tableName).keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableNames")
    @DisplayName("every column has the same Java type, jOOQ type and nullability in both generated models")
    void columnTypesAndNullabilityMatch(String tableName) {
        final Map<String, Field<?>> postgresColumns = columnsOf(POSTGRES_MODEL, tableName);
        final Map<String, Field<?>> clickHouseColumns = columnsOf(CLICKHOUSE_MODEL, tableName);

        postgresColumns.forEach((columnName, postgresColumn) -> {
            final Field<?> clickHouseColumn = clickHouseColumns.get(columnName);
            if (clickHouseColumn == null) {
                return; // reported by modelsDeclareTheSameColumns
            }
            final String qualified = tableName + "." + columnName;
            final DataType<?> postgresType = postgresColumn.getDataType();
            final DataType<?> clickHouseType = clickHouseColumn.getDataType();

            assertThat(clickHouseColumn.getType())
                    .as("Java type drift on '%s'. %s", qualified, WORKFLOW_HINT)
                    .isEqualTo(postgresColumn.getType());

            assertThat(clickHouseType.getTypeName())
                    .as("jOOQ type drift on '%s'. %s", qualified, WORKFLOW_HINT)
                    .isEqualTo(postgresType.getTypeName());

            assertThat(clickHouseType.length())
                    .as(
                            "Length drift on '%s' — VARCHAR(36) is load-bearing (the query schema infers 'uuid' "
                                    + "from it). %s",
                            qualified, WORKFLOW_HINT)
                    .isEqualTo(postgresType.length());

            assertThat(clickHouseType.precision())
                    .as("Precision drift on '%s'. %s", qualified, WORKFLOW_HINT)
                    .isEqualTo(postgresType.precision());

            assertThat(clickHouseType.scale())
                    .as("Scale drift on '%s'. %s", qualified, WORKFLOW_HINT)
                    .isEqualTo(postgresType.scale());

            assertThat(clickHouseType.nullable())
                    .as("Nullability drift on '%s'. %s", qualified, WORKFLOW_HINT)
                    .isEqualTo(postgresType.nullable());
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableNames")
    @DisplayName("every column's JSON-array default semantics are the same in both generated models")
    void jsonArrayDefaultSemanticsMatch(String tableName) {
        final Map<String, Field<?>> postgresColumns = columnsOf(POSTGRES_MODEL, tableName);
        final Map<String, Field<?>> clickHouseColumns = columnsOf(CLICKHOUSE_MODEL, tableName);

        postgresColumns.forEach((columnName, postgresColumn) -> {
            final Field<?> clickHouseColumn = clickHouseColumns.get(columnName);
            if (clickHouseColumn == null) {
                return; // reported by modelsDeclareTheSameColumns
            }
            assertThat(hasJsonArrayDefault(clickHouseColumn))
                    .as(
                            "JSON-array default drift on '%s.%s': JooqTableSchemaResolver publishes a JSONB column "
                                    + "as 'array' when its DDL default starts with '[ , else as 'object'. The two "
                                    + "vendors' default literals may differ ('[]'::jsonb vs '[]') but that decision "
                                    + "must not. %s",
                            tableName, columnName, WORKFLOW_HINT)
                    .isEqualTo(hasJsonArrayDefault(postgresColumn));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableNames")
    @DisplayName("the published query-field type of every column is the same in both generated models")
    void publishedQueryFieldTypesMatch(String tableName) {
        assertThat(queryFieldTypesOf(CLICKHOUSE_MODEL, tableName))
                .as(
                        "The query schema published for '%s' differs between the two generated analytics models, "
                                + "so the same API would answer differently per vendor. %s",
                        tableName, WORKFLOW_HINT)
                .isEqualTo(queryFieldTypesOf(POSTGRES_MODEL, tableName));
    }

    private static List<String> tableNamesOf(Schema model) {
        return model.getTables().stream().map(Table::getName).toList();
    }

    /** Column name → generated field, for one table of one model. */
    private static Map<String, Field<?>> columnsOf(Schema model, String tableName) {
        final Table<?> table = model.getTable(tableName);
        assertThat(table)
                .as("Table '%s' is missing from the '%s' model. %s", tableName, model, WORKFLOW_HINT)
                .isNotNull();
        final Map<String, Field<?>> columns = new LinkedHashMap<>();
        for (final Field<?> column : table.fields()) {
            columns.put(column.getName(), column);
        }
        return columns;
    }

    /** Column name → the {@link QueryFieldType} the schema endpoint publishes for it. */
    private static Map<String, QueryFieldType> queryFieldTypesOf(Schema model, String tableName) {
        return SCHEMA_RESOLVER.bindings(model.getTable(tableName)).values().stream()
                .collect(Collectors.toMap(QueryFieldBinding::name, QueryFieldBinding::type));
    }

    /** Mirrors {@code JooqTableSchemaResolver}'s array-inference rule. */
    private static boolean hasJsonArrayDefault(Field<?> column) {
        final DataType<?> dataType = column.getDataType();
        if (!dataType.defaulted()) {
            return false;
        }
        return String.valueOf(dataType.defaultValue())
                .trim()
                .toLowerCase(Locale.ROOT)
                .startsWith("'[");
    }
}
