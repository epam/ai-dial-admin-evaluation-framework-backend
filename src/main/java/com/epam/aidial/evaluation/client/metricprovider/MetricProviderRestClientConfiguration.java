package com.epam.aidial.evaluation.client.metricprovider;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds one RestClient per configured metric provider (baseUrl, timeouts).
 * No user token propagation; requests use the application identity.
 */
@Configuration
@LogExecution
public class MetricProviderRestClientConfiguration {

    @Bean
    public MetricProviderRestClientFactory metricProviderRestClientFactory(
            MetricProviderProperties properties, OpenTelemetry openTelemetry) {
        Map<String, RestClient> clients = properties.getProviders().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> buildRestClient(
                                entry.getValue().getBaseUrl(),
                                entry.getValue().getConnectTimeoutMs(),
                                entry.getValue().getReadTimeoutMs(),
                                openTelemetry)));
        return new MetricProviderRestClientFactory(clients);
    }

    private static RestClient buildRestClient(
            String baseUrl, int connectTimeoutMs, int readTimeoutMs, OpenTelemetry openTelemetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }
}
