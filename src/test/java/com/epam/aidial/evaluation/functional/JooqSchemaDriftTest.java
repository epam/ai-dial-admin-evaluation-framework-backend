package com.epam.aidial.evaluation.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.epam.aidial.evaluation.data.db.jooq.analytics.Tables;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Meta;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JooqSchemaDriftTest {

    private static EmbeddedPostgres embeddedPostgres;
    private static DataSource metaDataSource;
    private static DataSource analyticsDataSource;

    @BeforeAll
    static void startEmbeddedPostgres() throws IOException, SQLException {
        embeddedPostgres = EmbeddedPostgres.start();

        // Create both databases before connecting to them
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection()) {
            conn.createStatement().execute("CREATE DATABASE meta_db");
            conn.createStatement().execute("CREATE DATABASE analytics_db");
        }

        // Run meta migrations
        metaDataSource = embeddedPostgres.getDatabase("postgres", "meta_db");
        applyMigrations(metaDataSource, "db/migration/meta/POSTGRES", "public");

        // Run analytics migrations
        analyticsDataSource = embeddedPostgres.getDatabase("postgres", "analytics_db");
        applyMigrations(analyticsDataSource, "db/migration/analytics/POSTGRES", "public");
    }

    @AfterAll
    static void stopEmbeddedPostgres() throws IOException {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    private static void applyMigrations(DataSource ds, String location, String schema) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:" + location)
                .schemas(schema)
                .load()
                .migrate();
    }

    @Test
    void metaSchemaMatchesJooqGeneratedMetadata() {
        DSLContext dsl = DSL.using(metaDataSource, SQLDialect.POSTGRES);
        Meta dbMeta = dsl.meta();

        List<Table<?>> jooqTables = List.of(
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATIONS,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATION_VERSIONS,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.REVALIDATION_TASKS,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASE_RUN_INPUTS,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_METRIC_DEFINITIONS,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES,
                com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS);

        verifyTablesExistInDb(dsl, jooqTables, dbMeta);
    }

    @Test
    void analyticsSchemaMatchesJooqGeneratedMetadata() {
        DSLContext dsl = DSL.using(analyticsDataSource, SQLDialect.POSTGRES);
        Meta dbMeta = dsl.meta();

        List<Table<?>> jooqTables =
                List.of(Tables.TEST_CASE_EVAL_SUMMARIES, Tables.TEST_CASE_RUN_RESULTS, Tables.RUN_METRIC_SNAPSHOTS);

        verifyTablesExistInDb(dsl, jooqTables, dbMeta);
    }

    private void verifyTablesExistInDb(DSLContext dsl, List<Table<?>> jooqTables, Meta dbMeta) {
        // Get all DB tables
        Set<String> dbTableNames = dbMeta.getTables().stream()
                .map(t -> t.getName().toLowerCase())
                .filter(name -> !name.equals("flyway_schema_history"))
                .collect(Collectors.toSet());

        for (Table<?> jooqTable : jooqTables) {
            String tableName = jooqTable.getName().toLowerCase();

            assertThat(dbTableNames)
                    .as(
                            "jOOQ table '%s' not found in DB. Run './gradlew generateJooq' to regenerate sources.",
                            tableName)
                    .contains(tableName);

            // Get actual DB columns for this table
            Set<String> dbColumns = dbMeta.getTables().stream()
                    .filter(t -> t.getName().equalsIgnoreCase(tableName))
                    .findFirst()
                    .map(t -> Arrays.stream(t.fields())
                            .map(f -> f.getName().toLowerCase())
                            .collect(Collectors.toSet()))
                    .orElse(Set.of());

            Set<String> jooqColumns = Arrays.stream(jooqTable.fields())
                    .map(f -> f.getName().toLowerCase())
                    .collect(Collectors.toSet());

            // Verify each jOOQ-defined column exists in DB
            for (String colName : jooqColumns) {
                if (!dbColumns.contains(colName)) {
                    fail(
                            "Schema drift detected: jOOQ defines column '%s.%s' but it is missing in the DB. "
                                    + "Run './gradlew generateJooq' to regenerate sources.",
                            tableName, colName);
                }
            }

            // Verify each DB column is present in jOOQ metadata — catches new migration columns
            // that were added without regenerating the jOOQ sources.
            for (String dbColumn : dbColumns) {
                if (!jooqColumns.contains(dbColumn)) {
                    fail(
                            "Schema drift detected: DB column '%s.%s' is missing from jOOQ metadata. "
                                    + "Run './gradlew generateJooq' to regenerate sources.",
                            tableName, dbColumn);
                }
            }
        }
    }
}
