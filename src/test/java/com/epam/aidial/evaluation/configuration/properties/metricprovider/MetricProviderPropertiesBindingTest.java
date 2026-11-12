package com.epam.aidial.evaluation.configuration.properties.metricprovider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@DisplayName("MetricProviderProperties binding")
class MetricProviderPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "metric-providers.defaults.connect-timeout-ms=5000",
                    "metric-providers.defaults.read-timeout-ms=30000");

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

    @Test
    @DisplayName("a provider entry without its own timeouts falls back to metric-providers.defaults")
    void providerEntryWithoutTimeouts_usesDefaults() {
        runner.withPropertyValues(
                        "metric-providers.providers.custom.enabled=true",
                        "metric-providers.providers.custom.base-url=http://custom-metrics:8080",
                        "metric-providers.providers.tuned.enabled=true",
                        "metric-providers.providers.tuned.base-url=http://tuned-metrics:8080",
                        "metric-providers.providers.tuned.read-timeout-ms=150000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var properties = context.getBean(MetricProviderProperties.class);
                    final var custom = properties.getProviders().get("custom");
                    final var tuned = properties.getProviders().get("tuned");
                    assertThat(properties.resolveConnectTimeoutMs(custom)).isEqualTo(5000);
                    assertThat(properties.resolveReadTimeoutMs(custom)).isEqualTo(30000);
                    assertThat(properties.resolveConnectTimeoutMs(tuned)).isEqualTo(5000);
                    assertThat(properties.resolveReadTimeoutMs(tuned)).isEqualTo(150000);
                });
    }

    @Test
    @DisplayName("fails to start when metric-providers.defaults timeouts are missing")
    void missingDefaults_bindingFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("defaults.connectTimeoutMs")
                        .hasMessageContaining("defaults.readTimeoutMs"));
    }

    @Test
    @DisplayName("binds a provider declared purely via METRICPROVIDERS_PROVIDERS_<ID>_* environment variables")
    void providerDeclaredViaEnvironmentVariables_isBound() {
        runner.withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(
                                        "METRICPROVIDERS_PROVIDERS_CUSTOM_ENABLED", "true",
                                        "METRICPROVIDERS_PROVIDERS_CUSTOM_BASEURL", "http://custom-metrics:8080",
                                        "METRICPROVIDERS_PROVIDERS_CUSTOM_READTIMEOUTMS", "90000"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var properties = context.getBean(MetricProviderProperties.class);
                    final var custom = properties.getProviders().get("custom");
                    assertThat(properties.getProviders()).containsKey("custom");
                    assertThat(custom.getEnabled()).isTrue();
                    assertThat(custom.getBaseUrl()).isEqualTo("http://custom-metrics:8080");
                    assertThat(properties.resolveReadTimeoutMs(custom)).isEqualTo(90000);
                    assertThat(properties.resolveConnectTimeoutMs(custom)).isEqualTo(5000);
                });
    }
}
