package com.epam.aidial.evaluation.client.dialadas;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@LogExecution
public class DialAdasClientConfiguration {

    @Bean("dialAdasRestClient")
    public RestClient dialAdasRestClient(DialAdasProperties properties, OpenTelemetry openTelemetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(DialCoreClientConfiguration.authorizationTokenInterceptor())
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }
}
