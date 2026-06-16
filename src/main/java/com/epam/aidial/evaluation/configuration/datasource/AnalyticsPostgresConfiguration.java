package com.epam.aidial.evaluation.configuration.datasource;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.implementation.AccessTokenCache;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class AnalyticsPostgresConfiguration {

    private static final String AAD_DATABASE_SCOPE = "https://ossrdbms-aad.database.windows.net/.default";
    private static final TokenRequestContext TOKEN_REQUEST_CONTEXT =
            new TokenRequestContext().addScopes(AAD_DATABASE_SCOPE);

    @Bean
    @Qualifier("analyticsDataSource")
    @ConditionalOnProperty(value = "datasource.analytics.auth.type", havingValue = "basic")
    public DataSource analyticsDataSource(
            @Value("${postgres.analytics.datasource.url}") String url,
            @Value("${postgres.analytics.datasource.connection-params:}") String connectionParams,
            @Value("${postgres.analytics.datasource.driver-class-name}") String driverClassName,
            @Value("${postgres.analytics.datasource.username}") String username,
            @Value("${postgres.analytics.datasource.password}") String dbPassword,
            @Value("${postgres.analytics.datasource.schema:public}") String schema) {
        var ds = DataSourceBuilder.create()
                .type(com.zaxxer.hikari.HikariDataSource.class)
                .driverClassName(driverClassName)
                .url(buildJdbcUrl(url, connectionParams))
                .username(username)
                .password(dbPassword)
                .build();
        ds.setSchema(schema);
        return ds;
    }

    @Bean
    @Qualifier("analyticsDataSource")
    @ConditionalOnProperty(value = "datasource.analytics.auth.type", havingValue = "azure")
    public DataSource analyticsAzureDataSource(
            @Value("${postgres.analytics.datasource.url}") String url,
            @Value("${postgres.analytics.datasource.connection-params:}") String connectionParams,
            @Value("${postgres.analytics.datasource.driver-class-name}") String driverClassName,
            @Value("${postgres.analytics.datasource.username}") String username,
            @Value("${postgres.analytics.datasource.schema:public}") String schema,
            TokenCredential tokenCredential) {
        AccessTokenCache accessTokenCache = new AccessTokenCache(tokenCredential);
        Supplier<String> passwordProvider =
                () -> accessTokenCache.getTokenSync(TOKEN_REQUEST_CONTEXT, true).getToken();

        DynamicPasswordHikariDataSource dynamicPasswordHikariDataSource =
                new DynamicPasswordHikariDataSource(passwordProvider);
        dynamicPasswordHikariDataSource.setDriverClassName(driverClassName);
        dynamicPasswordHikariDataSource.setJdbcUrl(buildJdbcUrl(url, connectionParams));
        dynamicPasswordHikariDataSource.setUsername(username);
        dynamicPasswordHikariDataSource.setSchema(schema);

        return dynamicPasswordHikariDataSource;
    }

    private static String buildJdbcUrl(String baseUrl, String connectionParams) {
        if (connectionParams == null || connectionParams.isBlank()) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + connectionParams;
    }
}
