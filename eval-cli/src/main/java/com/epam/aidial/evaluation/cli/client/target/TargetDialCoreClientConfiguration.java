package com.epam.aidial.evaluation.cli.client.target;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Supplies the {@code "dialCoreTryOutRestClient"} and {@code "dialFileRestClient"} beans required by
 * {@code DialCoreDeploymentInvoker} and {@code DialFileClient} (in {@code evaluation-runner-core}).
 *
 * <p>Mirrors the EF backend's {@code DialCoreDeploymentInvokerConfiguration}/{@code
 * DialFileClientConfiguration} construction (same {@link JdkClientHttpRequestFactory}/timeout setup),
 * but authenticates with an {@code Api-Key} header rather than a bearer token, since this module has
 * no signed-in user session to propagate a JWT from. Both bind to the <em>target</em> environment's
 * DIAL Core host — file references encountered while executing a test case (e.g. an input binding
 * pointing at an uploaded file) are therefore resolved against the target DIAL Core, not the source
 * EF's. If a suite's file references must instead resolve against the source environment, this bean
 * would need to be reworked to use {@code eval.source}'s DIAL Core host/credentials.
 *
 * <p>{@code DialCoreDeploymentInvoker} builds absolute request URIs itself from the live
 * {@link DialCoreProperties#getBaseUrl()} value on every call, bypassing this RestClient's baked-in
 * {@code .baseUrl(...)}. The target host is entirely env-var-configured ({@code DIAL_CORE_URL}) — there
 * is no per-invocation CLI override.
 *
 * <p>Both beans build from the injected, Spring Boot-autoconfigured {@link RestClient.Builder} (a
 * prototype bean — each injection point gets its own instance) rather than the static {@code
 * RestClient.builder()} factory method, so response bodies are read using this module's {@code
 * JsonMapperConfiguration} bean instead of a bare default Jackson {@code ObjectMapper}.
 */
@Configuration
@LogExecution
public class TargetDialCoreClientConfiguration {

    @Bean("dialCoreTryOutRestClient")
    public RestClient dialCoreTryOutRestClient(RestClient.Builder builder, DialCoreProperties properties) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();

        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getTryOut().getReadTimeoutMs()));

        return builder.baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(apiKeyInterceptor())
                .build();
    }

    @Bean("dialFileRestClient")
    public RestClient dialFileRestClient(
            RestClient.Builder builder, DialCoreProperties coreProperties, DialFileStorageProperties fileProperties) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(fileProperties.getConnectTimeoutMs()))
                .build();

        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(fileProperties.getReadTimeoutMs()));

        return builder.baseUrl(coreProperties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(apiKeyInterceptor())
                .build();
    }

    /**
     * Reads the current API key from {@link AuthorizationTokenHolder} (populated per-worker-thread by
     * {@link com.epam.aidial.evaluation.runner.util.TokenPropagationHelper}), which sources it from
     * {@code TargetProperties#getApiKey()} (env var {@code DIAL_CORE_API_KEY}) via
     * {@link com.epam.aidial.evaluation.cli.service.EvaluationContextFactory}.
     */
    private static ClientHttpRequestInterceptor apiKeyInterceptor() {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            final String apiKey = AuthorizationTokenHolder.getToken();
            if (apiKey != null) {
                request.getHeaders().set("Api-Key", apiKey);
            }
            return execution.execute(request, body);
        };
    }
}
