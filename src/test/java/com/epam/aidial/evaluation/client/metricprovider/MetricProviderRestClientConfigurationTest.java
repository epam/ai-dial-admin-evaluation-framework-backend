package com.epam.aidial.evaluation.client.metricprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties.ProviderEntry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricProviderRestClientConfiguration")
class MetricProviderRestClientConfigurationTest {

    private static final String DIAL = "dial";
    private static final String EXTRA = "extra";

    private MetricProviderProperties properties;
    private MetricProviderRestClientConfiguration configuration;

    @BeforeEach
    void setUp() {
        properties = new MetricProviderProperties();
        configuration = new MetricProviderRestClientConfiguration();
    }

    private void givenProvider(String providerId, String baseUrl, boolean enabled) {
        final var entry = new ProviderEntry();
        entry.setEnabled(enabled);
        entry.setBaseUrl(baseUrl);
        properties.getProviders().put(providerId, entry);
    }

    private MetricProviderRestClientFactory buildFactory() {
        return configuration.metricProviderRestClientFactory(properties, OpenTelemetry.noop());
    }

    @Test
    @DisplayName("builds a distinct RestClient for every configured provider entry")
    void multipleProviders_oneClientPerEntry() {
        givenProvider(DIAL, "http://dial-metrics:8086", true);
        givenProvider(EXTRA, "http://extra-metrics:8087", true);

        final var factory = buildFactory();

        assertThat(factory.getRestClient(DIAL)).isPresent();
        assertThat(factory.getRestClient(EXTRA)).isPresent();
        assertThat(factory.getRestClient(DIAL).orElseThrow())
                .isNotSameAs(factory.getRestClient(EXTRA).orElseThrow());
    }

    @Test
    @DisplayName("builds a RestClient for a disabled entry too, so its already-synced metrics stay evaluable")
    void disabledProvider_clientStillBuilt() {
        givenProvider(DIAL, "http://dial-metrics:8086", true);
        givenProvider(EXTRA, "http://extra-metrics:8087", false);

        final var factory = buildFactory();

        assertThat(factory.getRestClient(EXTRA)).isPresent();
    }

    @Test
    @DisplayName("returns an empty client for a provider id that is not configured")
    void unknownProviderId_noClient() {
        givenProvider(DIAL, "http://dial-metrics:8086", true);

        final var factory = buildFactory();

        assertThat(factory.getRestClient("not-configured")).isEmpty();
    }

    @Test
    @DisplayName("builds no clients when the provider map is empty")
    void emptyProviderMap_noClients() {
        final var factory = buildFactory();

        assertThat(factory.getRestClient(DIAL)).isEmpty();
    }
}
