package com.epam.aidial.evaluation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClientConfiguration;
import com.epam.aidial.evaluation.configuration.properties.dialadas.DialAdasProperties;
import com.epam.aidial.evaluation.configuration.properties.query.QueryDslTestSuiteRunCostEnrichmentProperties;
import com.epam.aidial.evaluation.query.service.repository.TotalCostTestSuiteRunsPageExtender;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import com.epam.aidial.evaluation.service.domain.AdasCostQueryBuilder;
import com.epam.aidial.evaluation.service.domain.BatchRunTotalCostLookup;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Context-level proof of design D6 of {@code enrich-test-suite-runs-total-cost}: the enrichment
 * executor, dedicated dial-adas client/RestClient beans, and {@link TotalCostTestSuiteRunsPageExtender}
 * exist only when {@code query-dsl.enrichment.test-suite-run.cost.enabled=true}.
 */
@DisplayName("Test suite run cost enrichment conditional beans")
class TestSuiteRunCostEnrichmentConditionalBeansTest {

    private static final String EXECUTOR_BEAN = "testSuiteRunCostEnrichmentExecutor";
    private static final String REST_CLIENT_BEAN = "testSuiteRunCostEnrichmentDialAdasRestClient";
    private static final String CLIENT_BEAN = "testSuiteRunCostEnrichmentDialAdasClient";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SupportingBeansConfiguration.class,
                    TestSuiteRunCostEnrichmentAsyncConfiguration.class,
                    DialAdasClientConfiguration.class,
                    AdasCostQueryBuilder.class,
                    BatchRunTotalCostLookup.class,
                    TotalCostTestSuiteRunsPageExtender.class);

    @Configuration
    @EnableConfigurationProperties(QueryDslTestSuiteRunCostEnrichmentProperties.class)
    static class SupportingBeansConfiguration {

        @Bean
        RunExecutorFactory runExecutorFactory() {
            return new RunExecutorFactory(true);
        }

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetry.noop();
        }

        @Bean
        DialAdasProperties dialAdasProperties() {
            DialAdasProperties properties = new DialAdasProperties();
            properties.setBaseUrl("http://dial-adas.local");
            properties.setConnectTimeoutMs(5000);
            properties.setReadTimeoutMs(30000);
            return properties;
        }
    }

    @Test
    @DisplayName("default-disabled configuration registers neither the enrichment executor/client beans"
            + " nor the extender")
    void defaultDisabled_registersNoEnrichmentBeans() {
        runner.withPropertyValues(
                        "query-dsl.enrichment.test-suite-run.cost.enabled=false",
                        "query-dsl.enrichment.test-suite-run.cost.timeout-sec=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean(EXECUTOR_BEAN)).isFalse();
                    assertThat(context.containsBean(REST_CLIENT_BEAN)).isFalse();
                    assertThat(context.containsBean(CLIENT_BEAN)).isFalse();
                    assertThat(context.getBeansOfType(TotalCostTestSuiteRunsPageExtender.class))
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("explicitly-enabled configuration registers the enrichment executor, both enrichment client beans,"
            + " and the extender")
    void explicitlyEnabled_registersAllEnrichmentBeans() {
        runner.withPropertyValues(
                        "query-dsl.enrichment.test-suite-run.cost.enabled=true",
                        "query-dsl.enrichment.test-suite-run.cost.timeout-sec=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean(EXECUTOR_BEAN)).isTrue();
                    assertThat(context.containsBean(REST_CLIENT_BEAN)).isTrue();
                    assertThat(context.containsBean(CLIENT_BEAN)).isTrue();
                    assertThat(context.getBeansOfType(TotalCostTestSuiteRunsPageExtender.class))
                            .hasSize(1);
                });
    }
}
