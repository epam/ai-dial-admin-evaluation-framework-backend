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
 * health indicators) binds identically regardless of vendor.
 */
@Configuration
@Slf4j
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class AnalyticsClickHouseConfiguration {

    /**
     * {@code SET final = 1} makes every SELECT on this connection behave as if {@code FINAL} were
     * appended, so reads collapse {@code ReplacingMergeTree} duplicates without every repository
     * query having to say so explicitly. See plan decision 5 (dedup via ReplacingMergeTree +
     * session-wide FINAL reads).
     */
    private static final String FORCE_FINAL_READS_SQL = "SET final = 1";

    @Bean
    @Qualifier("analyticsDataSource")
    public DataSource analyticsDataSource(
            @Value("${clickhouse.analytics.datasource.url}") String url,
            @Value("${clickhouse.analytics.datasource.connection-params:}") String connectionParams,
            @Value("${clickhouse.analytics.datasource.driver-class-name}") String driverClassName,
            @Value("${clickhouse.analytics.datasource.username}") String username,
            @Value("${clickhouse.analytics.datasource.password}") String dbPassword) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(driverClassName)
                .url(buildJdbcUrl(url, connectionParams))
                .username(username)
                .password(dbPassword)
                .build();
        ds.setConnectionInitSql(FORCE_FINAL_READS_SQL);
        return ds;
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

    @Bean
    public Flyway analyticsFlywayMigration(
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            @Value("${clickhouse.analytics.datasource.database:evaluation_analytics}") String analyticsSchema,
            DatasourceValidationResult validationResult) {
        String location = "classpath:db/migration/analytics/CLICKHOUSE";
        log.info("Configuring analytics Flyway migration at location: {}, schema: {}", location, analyticsSchema);

        Flyway flyway = Flyway.configure()
                .dataSource(analyticsDataSource)
                .locations(location)
                .defaultSchema(analyticsSchema)
                .baselineOnMigrate(true)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
        return flyway;
    }

    private static String buildJdbcUrl(String baseUrl, String connectionParams) {
        if (connectionParams == null || connectionParams.isBlank()) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + connectionParams;
    }
}
