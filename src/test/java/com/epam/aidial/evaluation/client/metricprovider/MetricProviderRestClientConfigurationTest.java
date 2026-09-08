package com.epam.aidial.evaluation.client.metricprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties.ProviderEntry;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@DisplayName("MetricProviderRestClientConfiguration")
class MetricProviderRestClientConfigurationTest {

    private static final String DIAL = "dial";
    private static final String EXTRA = "extra";

    private MetricProviderProperties properties;
    private MetricProviderRestClientConfiguration configuration;
    private HttpServer blockingServer;
    private final CountDownLatch releaseHandler = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        properties = new MetricProviderProperties();
        configuration = new MetricProviderRestClientConfiguration();
    }

    @AfterEach
    void tearDown() {
        releaseHandler.countDown();
        if (blockingServer != null) {
            blockingServer.stop(0);
        }
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

    @Test
    @DisplayName("ends an in-flight call within 1s when its virtual thread is interrupted (interruptible client)")
    void interruptedInFlightCall_endsQuickly() throws Exception {
        final CountDownLatch requestReceived = new CountDownLatch(1);
        blockingServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        blockingServer.createContext("/", exchange -> {
            requestReceived.countDown();
            try {
                releaseHandler.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        blockingServer.start();

        givenProvider(DIAL, "http://localhost:" + blockingServer.getAddress().getPort(), true);
        final RestClient client = buildFactory().getRestClient(DIAL).orElseThrow();

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch callFinished = new CountDownLatch(1);
        final Thread worker = Thread.ofVirtual().start(() -> {
            try {
                client.post().uri("/evaluate").retrieve().body(String.class);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                callFinished.countDown();
            }
        });

        assertThat(requestReceived.await(2, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();

        assertThat(callFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNotNull();
    }
}
