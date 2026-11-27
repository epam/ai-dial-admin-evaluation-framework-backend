package com.epam.aidial.evaluation.client.scheduler;

import static com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration.callerCredentialInterceptor;
import static com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration.tracingInterceptor;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} used to call the DIAL Core scheduler application. It targets the same DIAL Core
 * base URL as the regular Core client and forwards the eval caller's credential.
 */
@Configuration
@LogExecution
@ConditionalOnProperty(name = "dial.scheduler.enabled", havingValue = "true")
public class SchedulerClientConfiguration {

    @Bean("dialSchedulerRestClient")
    public RestClient dialSchedulerRestClient(DialCoreProperties properties, OpenTelemetry openTelemetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(callerCredentialInterceptor())
                .requestInterceptor(tracingInterceptor(openTelemetry))
                .build();
    }
}
