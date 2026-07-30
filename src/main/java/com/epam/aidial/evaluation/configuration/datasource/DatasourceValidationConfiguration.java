package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.net.URI;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@LogExecution
public class DatasourceValidationConfiguration {

    private static final int POSTGRES_DEFAULT_PORT = 5432;
    private static final Set<String> SUPPORTED_ANALYTICS_VENDORS = Set.of("POSTGRES");

    @Bean
    public DatasourceValidationResult datasourceValidationResult(
            @Value("${postgres.meta.datasource.url}") String metaUrl,
            @Value("${postgres.analytics.datasource.url}") String analyticsUrl,
            @Value("${postgres.meta.datasource.schema:public}") String metaSchema,
            @Value("${postgres.analytics.datasource.schema:public}") String analyticsSchema,
            @Value("${datasource.analytics.vendor}") String analyticsVendor) {

        validateSupportedVendor(analyticsVendor);
        validateDatasourceIsolation(metaUrl, analyticsUrl, metaSchema, analyticsSchema);

        return new DatasourceValidationResult(true);
    }

    private void validateSupportedVendor(String analyticsVendor) {
        if (!SUPPORTED_ANALYTICS_VENDORS.contains(analyticsVendor)) {
            throw new IllegalStateException("Analytics vendor '" + analyticsVendor
                    + "' is not yet supported. Supported vendors: " + String.join(", ", SUPPORTED_ANALYTICS_VENDORS));
        }
    }

    private void validateDatasourceIsolation(
            String metaUrl, String analyticsUrl, String metaSchema, String analyticsSchema) {
        JdbcUrlComponents metaComponents = parseJdbcUrl(metaUrl);
        JdbcUrlComponents analyticsComponents = parseJdbcUrl(analyticsUrl);

        boolean sameHost = metaComponents.host().equalsIgnoreCase(analyticsComponents.host());
        boolean samePort = metaComponents.port() == analyticsComponents.port();
        boolean sameDatabase = metaComponents.database().equalsIgnoreCase(analyticsComponents.database());
        boolean sameSchema = metaSchema.equalsIgnoreCase(analyticsSchema);

        if (sameHost && samePort && sameDatabase && sameSchema) {
            throw new IllegalStateException(
                    "Meta and analytics datasources must use separate databases or separate schemas. "
                            + "Both are configured to point to the same database '"
                            + metaComponents.database() + "' with schema '" + metaSchema + "'. "
                            + "Note: DNS-level equivalences (e.g., localhost vs 127.0.0.1) are not resolved — "
                            + "only syntactic normalization is performed.");
        }

        if (sameHost && samePort && sameDatabase) {
            log.info(
                    "Meta and analytics datasources share the same database '{}' "
                            + "but use different schemas: meta='{}', analytics='{}'",
                    metaComponents.database(),
                    metaSchema,
                    analyticsSchema);
        }
    }

    static JdbcUrlComponents parseJdbcUrl(String jdbcUrl) {
        // Expected format: jdbc:postgresql://host[:port]/database[?params]
        String url = jdbcUrl;
        if (url.startsWith("jdbc:")) {
            url = url.substring(5);
        }
        if (url.startsWith("postgresql://")) {
            url = url.substring("postgresql://".length());
        } else {
            throw new IllegalArgumentException("Unsupported JDBC URL format: " + jdbcUrl);
        }

        // Split on '/' to separate host:port from database
        int slashIdx = url.indexOf('/');
        if (slashIdx < 0) {
            throw new IllegalArgumentException("Cannot parse database from JDBC URL: " + jdbcUrl);
        }

        String hostPort = url.substring(0, slashIdx);
        String rest = url.substring(slashIdx + 1);

        // Remove query params from database name
        int queryIdx = rest.indexOf('?');
        String database = queryIdx >= 0 ? rest.substring(0, queryIdx) : rest;

        // Parse host and port
        String host;
        int port;
        try {
            URI uri = URI.create("dummy://" + hostPort);
            host = uri.getHost() != null ? uri.getHost() : hostPort;
            port = uri.getPort() > 0 ? uri.getPort() : POSTGRES_DEFAULT_PORT;
        } catch (Exception e) {
            // Fallback: manual parsing
            int colonIdx = hostPort.lastIndexOf(':');
            if (colonIdx > 0) {
                host = hostPort.substring(0, colonIdx);
                try {
                    port = Integer.parseInt(hostPort.substring(colonIdx + 1));
                } catch (NumberFormatException nfe) {
                    host = hostPort;
                    port = POSTGRES_DEFAULT_PORT;
                }
            } else {
                host = hostPort;
                port = POSTGRES_DEFAULT_PORT;
            }
        }

        return new JdbcUrlComponents(host, port, database);
    }

    record JdbcUrlComponents(String host, int port, String database) {}
}
