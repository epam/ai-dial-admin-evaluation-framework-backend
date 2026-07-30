package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Stays in the EF backend even though {@code DialCoreDeploymentInvoker} (the sole consumer of the
 * {@code "dialCoreTryOutRestClient"} bean) moved to the shared module: this class composes
 * {@link DialCoreClientConfiguration}'s interceptor factories, and moving it would create a
 * shared-module → EF-backend compile dependency, which is forbidden. The invoker consumes the bean by
 * qualifier name only (a runtime lookup), so the physical split has no wiring cost — Spring resolves it
 * from the same application context regardless of which module declares it.
 */
@Configuration
@LogExecution
public class DialCoreDeploymentInvokerConfiguration {

    @Bean("dialCoreTryOutRestClient")
    public RestClient dialCoreTryOutRestClient(DialCoreProperties properties, OpenTelemetry openTelemetry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getTryOut().getReadTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(DialCoreClientConfiguration.authorizationTokenInterceptor())
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }
}
