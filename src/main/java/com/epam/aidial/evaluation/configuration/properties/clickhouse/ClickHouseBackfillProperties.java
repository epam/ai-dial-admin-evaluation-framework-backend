package com.epam.aidial.evaluation.configuration.properties.clickhouse;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the one-time Postgres → ClickHouse analytics data backfill, executed by
 * {@code ClickHouseAnalyticsBackfillMigration} (a repeatable Flyway Java migration) when
 * {@code datasource.analytics.vendor=CLICKHOUSE}.
 *
 * <p>The Postgres coordinates here point at the <b>old analytics Postgres database</b> being migrated
 * away from — not the meta database. They are deliberately separate from
 * {@code postgres.analytics.datasource.*}: the backfill should run with a dedicated read-only Postgres
 * user, and the analytics JDBC URL (which may carry Azure AD auth, pooling params, etc.) is not a safe
 * thing to parse host/port out of.
 *
 * <p>Field-level requiredness is conditional (everything is optional while {@code enabled=false}), so
 * it is validated by the migration itself at execution time rather than with Bean Validation here.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "clickhouse.analytics.backfill")
public class ClickHouseBackfillProperties {

    /** Whether the backfill runs on the next startup. Leave {@code false} except for the cutover deploy. */
    private boolean enabled;

    private Postgres postgres;

    /** Coordinates of the source analytics Postgres database, as consumed by ClickHouse's {@code postgresql()} table function. */
    @Getter
    @Setter
    public static class Postgres {

        /** Hostname the ClickHouse <b>server</b> (not this application) must be able to reach. */
        private String host;

        private Integer port;

        private String database;

        private String schema;

        private String username;

        private String password;
    }
}
