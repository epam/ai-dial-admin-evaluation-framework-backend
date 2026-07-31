package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.configuration.properties.dial.DialProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Stays in the EF backend even though {@code DialFileClient}/{@code DialFileRefResolver} (the consumers
 * of the {@code "dialFileRestClient"} bean) moved to the shared module: this class depends on
 * {@link DialProperties} (an EF-backend-only general config) and {@link DialCoreClientConfiguration}'s
 * tracing interceptor. The consumers reference the bean by qualifier name only (a runtime lookup), so the
 * physical split has no wiring cost.
 */
@Configuration
@LogExecution
public class DialFileClientConfiguration {

    @Bean("dialFileRestClient")
    public RestClient dialFileRestClient(
            DialCoreProperties coreProperties,
            DialFileStorageProperties fileProperties,
            DialProperties dialProperties,
            OpenTelemetry openTelemetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(fileProperties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(fileProperties.getReadTimeoutMs()));

        return RestClient.builder()
                .baseUrl(coreProperties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(apiKeyInterceptor(dialProperties))
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }

    static ClientHttpRequestInterceptor apiKeyInterceptor(DialProperties properties) {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            request.getHeaders().set("Api-Key", properties.getApiKey());
            return execution.execute(request, body);
        };
    }
}
