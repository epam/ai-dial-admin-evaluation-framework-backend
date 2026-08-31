package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Applies the ClickHouse analytics schema at startup, in place of Flyway.
 *
 * <p><b>Why not Flyway.</b> The only ClickHouse Flyway plugin
 * ({@code org.flywaydb:flyway-database-clickhouse}) probes for schema existence with
 * {@code SELECT COUNT() FROM system.databases WHERE name = ?}. The ClickHouse V2 JDBC driver parses every
 * {@code PreparedStatement} with an ANTLR grammar that cannot parse a bare {@code name} column reference,
 * so the parse fails, the driver reports zero bind parameters, and {@code setString(1, …)} dies with
 * {@code ArrayIndexOutOfBoundsException} before any SQL reaches the server. The failure is in the plugin's
 * hard-coded SQL, so no Flyway configuration avoids it.
 *
 * <p><b>Contract for the scripts.</b> There is no schema-history table: every script under
 * {@link #SCHEMA_LOCATION} is re-executed on every startup, in filename order, so <b>every statement must
 * be idempotent</b> ({@code CREATE TABLE IF NOT EXISTS}, {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS},
 * …). ClickHouse DDL auto-commits per statement, and analytics tables carry no data the application cannot
 * recompute, so a re-run is safe by construction.
 */
@Slf4j
@LogExecution
public class ClickHouseSchemaInitializer {

    private static final String SCHEMA_LOCATION = "classpath*:db/migration/analytics/CLICKHOUSE/*.sql";

    private final DataSource dataSource;

    public ClickHouseSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Executes every schema script in filename order. Fails fast: a broken schema is not recoverable. */
    public void initialize() {
        Resource[] scripts = resolveScripts();
        log.info("Applying {} ClickHouse analytics schema script(s) from {}", scripts.length, SCHEMA_LOCATION);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(scripts);
        populator.setSeparator(";");
        populator.setCommentPrefix("--");
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);

        log.info("ClickHouse analytics schema is up to date");
    }

    private Resource[] resolveScripts() {
        try {
            Resource[] scripts = new PathMatchingResourcePatternResolver().getResources(SCHEMA_LOCATION);
            if (scripts.length == 0) {
                throw new IllegalStateException("No ClickHouse analytics schema scripts found at " + SCHEMA_LOCATION);
            }
            Arrays.sort(scripts, Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve ClickHouse analytics schema scripts", e);
        }
    }
}
