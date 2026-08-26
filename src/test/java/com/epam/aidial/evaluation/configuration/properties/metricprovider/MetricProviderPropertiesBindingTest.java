package com.epam.aidial.evaluation.configuration.properties.metricprovider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("MetricProviderProperties binding")
class MetricProviderPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Configuration
    @EnableConfigurationProperties(MetricProviderProperties.class)
    static class TestConfiguration {}

    @Test
    @DisplayName("binds several provider entries keyed by provider id, keeping per-entry enabled flags and timeouts")
    void multipleProviders_boundWithOwnEnabledFlag() {
        runner.withPropertyValues(
                        "metric-providers.providers.dial.enabled=true",
                        "metric-providers.providers.dial.base-url=http://dial-metrics:8086",
                        "metric-providers.providers.dial.connect-timeout-ms=5000",
                        "metric-providers.providers.dial.read-timeout-ms=150000",
                        "metric-providers.providers.extra.enabled=false",
                        "metric-providers.providers.extra.base-url=http://extra-metrics:8087")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var providers =
                            context.getBean(MetricProviderProperties.class).getProviders();
                    assertThat(providers).containsOnlyKeys("dial", "extra");
                    assertThat(providers.get("dial").getEnabled()).isTrue();
                    assertThat(providers.get("dial").getBaseUrl()).isEqualTo("http://dial-metrics:8086");
                    assertThat(providers.get("dial").getReadTimeoutMs()).isEqualTo(150000);
                    assertThat(providers.get("extra").getEnabled()).isFalse();
                    assertThat(providers.get("extra").getBaseUrl()).isEqualTo("http://extra-metrics:8087");
                });
    }

    @Test
    @DisplayName("fails to start when a provider entry omits the enabled flag")
    void providerEntryWithoutEnabled_bindingFails() {
        runner.withPropertyValues("metric-providers.providers.custom.base-url=http://custom-metrics:8080")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("providers[custom].enabled")
                        .hasMessageContaining("must not be null"));
    }

    @Test
    @DisplayName("fails to start when a provider entry omits the base URL")
    void providerEntryWithoutBaseUrl_bindingFails() {
        runner.withPropertyValues("metric-providers.providers.custom.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("Metric provider base-url is required"));
    }
}
