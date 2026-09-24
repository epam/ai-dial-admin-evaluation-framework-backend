package com.epam.aidial.evaluation.client.dialadas;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.configuration.properties.dialadas.DialAdasProperties;
import com.epam.aidial.evaluation.configuration.properties.query.QueryDslTestSuiteRunCostExtensionProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
                .requestInterceptor(DialCoreClientConfiguration.callerCredentialInterceptor())
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }

    /**
     * Short-timeout {@link RestClient} used only by the conditional {@code total_cost} result-page
     * extension (design D3 of {@code enrich-test-suite-runs-total-cost}). Reuses
     * {@link DialAdasProperties#getBaseUrl()} and the same shared caller-credential/tracing
     * interceptors as {@link #dialAdasRestClient}, but sets both connect and read timeouts to the
     * configured {@code query-dsl.extension.test-suite-run.cost.timeout-sec} as a cleanup backstop —
     * it never changes {@link #dialAdasRestClient}'s normal timeout behavior.
     */
    @Bean("testSuiteRunCostExtensionDialAdasRestClient")
    @ConditionalOnProperty(
            prefix = QueryDslTestSuiteRunCostExtensionProperties.PREFIX,
            name = "enabled",
            havingValue = "true")
    public RestClient testSuiteRunCostExtensionDialAdasRestClient(
            DialAdasProperties properties,
            OpenTelemetry openTelemetry,
            QueryDslTestSuiteRunCostExtensionProperties extensionProperties) {
        Duration timeout = Duration.ofSeconds(extensionProperties.getTimeoutSec());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(DialCoreClientConfiguration.callerCredentialInterceptor())
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }

    /**
     * Conditional, named {@link DialAdasClient} instance built explicitly around
     * {@link #testSuiteRunCostExtensionDialAdasRestClient} rather than scanned, so only the qualified
     * injection point in {@code TotalCostTestSuiteRunsPageExtender} ever receives the short-timeout
     * client; every existing unqualified consumer keeps resolving to the {@code @Primary} scanned
     * {@link DialAdasClient}.
     */
    @Bean("testSuiteRunCostExtensionDialAdasClient")
    @ConditionalOnProperty(
            prefix = QueryDslTestSuiteRunCostExtensionProperties.PREFIX,
            name = "enabled",
            havingValue = "true")
    public DialAdasClient testSuiteRunCostExtensionDialAdasClient(
            @Qualifier("testSuiteRunCostExtensionDialAdasRestClient") RestClient restClient) {
        return new DialAdasClient(restClient);
    }
}
