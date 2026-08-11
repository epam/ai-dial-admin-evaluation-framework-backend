package com.epam.aidial.evaluation.cli.client.source;

import com.epam.aidial.evaluation.cli.config.properties.SourceProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures the {@link RestClient} used to call the source EF instance.
 *
 * <p>Builds from the injected, Spring Boot-autoconfigured {@link RestClient.Builder} (a prototype
 * bean) rather than the static {@code RestClient.builder()} factory method — the latter bypasses
 * {@code HttpMessageConvertersAutoConfiguration} entirely and falls back to a bare default Jackson
 * {@code ObjectMapper} instead of this module's {@code JsonMapperConfiguration} bean, breaking
 * deserialization of any response containing a JSON {@code null} for a primitive field.
 */
@Configuration
@LogExecution
public class SourceClientConfiguration {

    @Bean("sourceRestClient")
    public RestClient sourceRestClient(RestClient.Builder builder, SourceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        return builder.baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(staticApiKeyInterceptor(properties.getApiKey()))
                .build();
    }

    /**
     * Returns a request interceptor that sets a static {@code Api-Key} header.
     *
     * <p>This is the source-EF API key, distinct from the target DIAL Core API key.
     */
    static ClientHttpRequestInterceptor staticApiKeyInterceptor(String apiKey) {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            request.getHeaders().set("Api-Key", apiKey);
            return execution.execute(request, body);
        };
    }
}
