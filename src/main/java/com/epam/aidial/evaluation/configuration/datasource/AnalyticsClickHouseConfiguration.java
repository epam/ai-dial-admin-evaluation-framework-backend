package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
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
 * health indicators) binds identically regardless of vendor. The one shape that differs is schema
 * management: {@link ClickHouseSchemaInitializer} replaces the Flyway bean.
 */
@Configuration
@Slf4j
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class AnalyticsClickHouseConfiguration {

    /**
     * ClickHouse connection property that pins the server-side {@code final} setting to 1 for every query
     * issued through this datasource, so reads collapse {@code ReplacingMergeTree} duplicates without every
     * repository query having to say {@code FINAL} explicitly (plan decision 5).
     *
     * <p>It is deliberately a <b>connection property</b>, not {@code connectionInitSql = "SET final = 1"}:
     * the ClickHouse V2 driver sends each statement as an independent, stateless HTTP request, so a
     * {@code SET} executed once per pooled connection is silently forgotten — verified against a live
     * server, where {@code system.settings} still reported {@code final = 0} and a duplicated
     * {@code ReplacingMergeTree} key still returned two rows. The {@code clickhouse_setting_} prefix makes
     * the driver attach the setting to every request instead.
     */
    private static final String FORCE_FINAL_READS_PARAM = "clickhouse_setting_final=1";

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
                .url(buildJdbcUrl(url, appendFinalReadsParam(connectionParams)))
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
     * Applies the analytics schema. Deliberately not Flyway — see {@link ClickHouseSchemaInitializer} for
     * why the ClickHouse Flyway plugin cannot run on the V2 JDBC driver. The unused
     * {@link DatasourceValidationResult} parameter preserves the bean-ordering contract of
     * {@link AnalyticsFlywayConfiguration}: schema work never starts before datasource validation passes.
     */
    @Bean
    public ClickHouseSchemaInitializer analyticsSchemaInitializer(
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            DatasourceValidationResult validationResult) {
        ClickHouseSchemaInitializer initializer = new ClickHouseSchemaInitializer(analyticsDataSource);
        initializer.initialize();
        return initializer;
    }

    private static String appendFinalReadsParam(String connectionParams) {
        if (connectionParams == null || connectionParams.isBlank()) {
            return FORCE_FINAL_READS_PARAM;
        }
        return connectionParams + "&" + FORCE_FINAL_READS_PARAM;
    }

    private static String buildJdbcUrl(String baseUrl, String connectionParams) {
        if (connectionParams == null || connectionParams.isBlank()) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + connectionParams;
    }
}
