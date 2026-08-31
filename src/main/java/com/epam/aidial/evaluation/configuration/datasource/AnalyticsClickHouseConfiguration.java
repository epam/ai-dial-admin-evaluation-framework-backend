package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jooq.autoconfigure.ExceptionTranslatorExecuteListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Analytics datasource wiring for {@code datasource.analytics.vendor=CLICKHOUSE}. Mirrors the bean
 * names/shapes of {@link AnalyticsJdbcConfiguration} and {@link AnalyticsPostgresConfiguration} /
 * {@link AnalyticsFlywayConfiguration} so the rest of the analytics stack (repositories, services,
 * health indicators) binds identically regardless of vendor, including schema management: this class
 * runs a {@code flyway-database-clickhouse} {@link Flyway} bean shaped exactly like
 * {@link AnalyticsFlywayConfiguration}'s.
 */
@Configuration
@Slf4j
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class AnalyticsClickHouseConfiguration {

    /**
     * ClickHouse server settings that every query through this datasource must run with, so that the
     * repository SQL shared with Postgres keeps its meaning.
     *
     * <p>They are <b>connection properties</b>, not {@code connectionInitSql = "SET ..."}: the ClickHouse
     * V2 driver sends each statement as an independent, stateless HTTP request, so a {@code SET} executed
     * once per pooled connection is silently forgotten — verified against a live server, where
     * {@code system.settings} still reported {@code final = 0} and a duplicated {@code ReplacingMergeTree}
     * key still returned two rows. The {@code clickhouse_setting_} prefix makes the driver attach the
     * setting to every request instead.
     *
     * <ul>
     *   <li>{@code final=1} — reads collapse {@code ReplacingMergeTree} duplicates without every repository
     *       query having to say {@code FINAL} explicitly (plan decision 5).
     *   <li>{@code join_use_nulls=1} — ClickHouse's default fills the right-hand columns of an unmatched
     *       {@code LEFT JOIN} row with the column type's <i>default value</i> ({@code ''} for
     *       {@code String}), not {@code NULL}. Every anti-match predicate in the run-comparison queries
     *       ({@code probeKey IS NULL} / {@code IS NOT NULL}, {@code count(probeKey)}) would then read as
     *       "everything matched". This setting restores SQL-standard join nullability.
     * </ul>
     */
    private static final String REQUIRED_SERVER_SETTINGS =
            "clickhouse_setting_final=1&clickhouse_setting_join_use_nulls=1";

    @Bean
    @Qualifier("analyticsDataSource")
    public DataSource analyticsDataSource(
            @Value("${clickhouse.analytics.datasource.url}") String url,
            @Value("${clickhouse.analytics.datasource.connection-params:}") String connectionParams,
            @Value("${clickhouse.analytics.datasource.driver-class-name}") String driverClassName,
            @Value("${clickhouse.analytics.datasource.username}") String username,
            @Value("${clickhouse.analytics.datasource.password}") String dbPassword) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(driverClassName)
                .url(buildJdbcUrl(url, withRequiredServerSettings(connectionParams)))
                .username(username)
                .password(dbPassword)
                .build();
    }

    @Bean
    @Qualifier("analyticsRawJdbcTemplate")
    public JdbcTemplate analyticsRawJdbcTemplate(@Qualifier("analyticsDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier("analyticsJdbcTemplate")
    public NamedParameterJdbcTemplate analyticsJdbcTemplate(@Qualifier("analyticsDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier("analyticsTransactionManager")
    public PlatformTransactionManager analyticsTransactionManager() {
        return new ClickHouseNoOpTransactionManager();
    }

    @Bean
    @Qualifier("analyticsDsl")
    public DSLContext analyticsDsl(@Qualifier("analyticsDataSource") DataSource dataSource) {
        DefaultConfiguration config = new DefaultConfiguration();
        config.setDataSource(new TransactionAwareDataSourceProxy(dataSource));
        config.setSQLDialect(SQLDialect.CLICKHOUSE);
        config.setSettings(new Settings().withRenderSchema(false));
        config.setExecuteListenerProvider(
                new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
        return DSL.using(config);
    }

    /**
     * Applies the analytics schema via Flyway, mirroring {@link AnalyticsFlywayConfiguration}'s shape.
     * The unused {@link DatasourceValidationResult} parameter preserves the same bean-ordering contract:
     * schema work never starts before datasource validation passes.
     *
     * <p>Requires clickhouse-jdbc &ge; 0.10.0 (0.9.0's ANTLR statement parser could not parse the
     * plugin's schema-existence probe, which forced a hand-rolled {@code ClickHouseSchemaInitializer}
     * workaround for a while — since removed) <b>and</b> a {@code jdbc:clickhouse://} URL:
     * {@code flyway-database-clickhouse}'s {@code ClickHouseDatabaseType.handlesJDBCUrl} only claims the
     * {@code jdbc:clickhouse:} prefix, not the driver's shorter {@code jdbc:ch:} alias — the plugin never
     * recognizes the database type on a {@code jdbc:ch://} URL, so every documented default and test
     * fixture here uses the long prefix. The application itself still accepts both prefixes
     * ({@link DatasourceValidationConfiguration#parseJdbcUrl}).
     */
    @Bean
    public Flyway analyticsFlywayMigration(
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            @Value("${clickhouse.analytics.datasource.database:evaluation_analytics}") String analyticsDatabase,
            DatasourceValidationResult validationResult) {
        String location = "classpath:db/migration/analytics/CLICKHOUSE";
        log.info("Configuring analytics Flyway migration at location: {}, schema: {}", location, analyticsDatabase);

        Flyway flyway = Flyway.configure()
                .dataSource(analyticsDataSource)
                .locations(location)
                .defaultSchema(analyticsDatabase)
                .baselineOnMigrate(true)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
        return flyway;
    }

    private static String withRequiredServerSettings(String connectionParams) {
        if (connectionParams == null || connectionParams.isBlank()) {
            return REQUIRED_SERVER_SETTINGS;
        }
        return connectionParams + "&" + REQUIRED_SERVER_SETTINGS;
    }

    private static String buildJdbcUrl(String baseUrl, String connectionParams) {
        if (connectionParams == null || connectionParams.isBlank()) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + connectionParams;
    }
}
