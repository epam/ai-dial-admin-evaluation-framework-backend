package com.epam.aidial.evaluation.configuration.properties.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("QueryDslTestSuiteRunCostExtensionProperties binding")
class QueryDslTestSuiteRunCostExtensionPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Configuration
    @EnableConfigurationProperties(QueryDslTestSuiteRunCostExtensionProperties.class)
    static class TestConfiguration {}

    @Test
    @DisplayName("binds the real application.yml defaults as disabled with a 2 second timeout")
    void applicationYmlDefaults_bindAsDisabledWithTwoSecondTimeout() throws Exception {
        final List<PropertySource<?>> applicationYml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        runner.withInitializer(context -> applicationYml.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var properties = context.getBean(QueryDslTestSuiteRunCostExtensionProperties.class);
                    assertThat(properties.getEnabled()).isFalse();
                    assertThat(properties.getTimeoutSec()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("an explicit override enables extension and keeps the configured timeout")
    void explicitOverride_bindsEnabledWithConfiguredTimeout() {
        runner.withPropertyValues(
                        "query-dsl.extension.test-suite-run.cost.enabled=true",
                        "query-dsl.extension.test-suite-run.cost.timeout-sec=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var properties = context.getBean(QueryDslTestSuiteRunCostExtensionProperties.class);
                    assertThat(properties.getEnabled()).isTrue();
                    assertThat(properties.getTimeoutSec()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("fails to start when timeout-sec is below 1")
    void timeoutBelowOne_bindingFails() {
        runner.withPropertyValues(
                        "query-dsl.extension.test-suite-run.cost.enabled=true",
                        "query-dsl.extension.test-suite-run.cost.timeout-sec=0")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("timeoutSec"));
    }
}
