package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@LogExecution
public class DialCoreClientConfiguration {

    @Bean("dialCoreRestClient")
    public RestClient dialCoreRestClient(DialCoreProperties properties, OpenTelemetry openTelemetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(authorizationTokenInterceptor())
                .requestInterceptor(tracingInterceptor(openTelemetry))
                .build();
    }

    public static ClientHttpRequestInterceptor authorizationTokenInterceptor() {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            String token = AuthorizationTokenHolder.getToken();
            if (token != null) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        };
    }

    public static ClientHttpRequestInterceptor tracingInterceptor(OpenTelemetry openTelemetry) {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            openTelemetry
                    .getPropagators()
                    .getTextMapPropagator()
                    .inject(
                            Context.current(),
                            request,
                            (r, key, value) -> r.getHeaders().set(key, value));
            return execution.execute(request, body);
        };
    }
}
