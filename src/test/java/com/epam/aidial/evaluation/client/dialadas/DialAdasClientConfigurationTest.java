package com.epam.aidial.evaluation.client.dialadas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasRunAvgCostRowDto;
import com.epam.aidial.evaluation.configuration.properties.dialadas.DialAdasProperties;
import com.epam.aidial.evaluation.configuration.properties.query.QueryDslTestSuiteRunCostEnrichmentProperties;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Proves design D3 of {@code enrich-test-suite-runs-total-cost}: the conditional, named
 * {@code testSuiteRunCostEnrichmentDialAdasRestClient}/{@code testSuiteRunCostEnrichmentDialAdasClient}
 * beans reuse {@link DialAdasProperties#getBaseUrl()} and the shared caller-credential interceptor but
 * apply the configured enrichment {@code timeoutSec} as connect/read timeouts, while the normal
 * {@link DialAdasClientConfiguration#dialAdasRestClient} bean remains driven only by
 * {@link DialAdasProperties} and is unaffected by the enrichment configuration.
 */
@DisplayName("DialAdasClientConfiguration cost-enrichment beans")
class DialAdasClientConfigurationTest {

    private final DialAdasClientConfiguration configuration = new DialAdasClientConfiguration();

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    private static DialAdasProperties dialAdasProperties(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        DialAdasProperties properties = new DialAdasProperties();
        properties.setBaseUrl(baseUrl);
        properties.setConnectTimeoutMs(connectTimeoutMs);
        properties.setReadTimeoutMs(readTimeoutMs);
        return properties;
    }

    private static QueryDslTestSuiteRunCostEnrichmentProperties enrichmentProperties(int timeoutSec) {
        QueryDslTestSuiteRunCostEnrichmentProperties properties = new QueryDslTestSuiteRunCostEnrichmentProperties();
        properties.setEnabled(true);
        properties.setTimeoutSec(timeoutSec);
        return properties;
    }

    @Test
    @DisplayName(
            "testSuiteRunCostEnrichmentDialAdasRestClient reuses the base URL and registers the caller-credential interceptor")
    void enrichmentRestClient_reusesBaseUrlAndRegistersInterceptor() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-enrichment"));
        DialAdasProperties properties = dialAdasProperties("http://dial-adas.local", 5000, 30000);

        RestClient restClient = configuration.testSuiteRunCostEnrichmentDialAdasRestClient(
                properties, OpenTelemetry.noop(), enrichmentProperties(2));

        RestClient.Builder mutated = restClient.mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient testClient = mutated.build();
        server.expect(requestTo("http://dial-adas.local/ping"))
                .andExpect(header(CallerCredential.API_KEY_HEADER, "key-enrichment"))
                .andRespond(withSuccess());

        testClient.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("testSuiteRunCostEnrichmentDialAdasClient delegates to the given qualified RestClient")
    void enrichmentClient_delegatesToGivenRestClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://dial-adas.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("http://dial-adas.local/v1/queries/execute"))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        DialAdasClient enrichmentClient = configuration.testSuiteRunCostEnrichmentDialAdasClient(restClient);
        StructuredQuery query =
                new StructuredQuery("dial_usage_log", null, QueryMode.AGGREGATE, false, null, null, null, null, null);
        AdasAggregateResponseDto<AdasRunAvgCostRowDto> response =
                enrichmentClient.executeAggregate(query, AdasRunAvgCostRowDto.class);

        assertThat(response.getRows()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("enrichment client's timeoutSec bounds a stalled request as a cleanup backstop")
    void enrichmentRestClient_appliesConfiguredReadTimeout() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread acceptThread = new Thread(() -> acceptAndStall(serverSocket, 5000));
            acceptThread.setDaemon(true);
            acceptThread.start();

            DialAdasProperties properties =
                    dialAdasProperties("http://localhost:" + serverSocket.getLocalPort(), 5000, 30000);
            RestClient restClient = configuration.testSuiteRunCostEnrichmentDialAdasRestClient(
                    properties, OpenTelemetry.noop(), enrichmentProperties(1));

            long start = System.nanoTime();
            assertThatThrownBy(() -> restClient
                            .post()
                            .uri("/v1/queries/execute")
                            .body("{}")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(3000);
        }
    }

    @Test
    @DisplayName(
            "normal dialAdasRestClient keeps its own longer configured timeout, unaffected by enrichment configuration")
    void normalRestClient_unaffectedByEnrichmentConfiguration() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread acceptThread = new Thread(() -> acceptStallThenRespond(serverSocket, 1500));
            acceptThread.setDaemon(true);
            acceptThread.start();

            DialAdasProperties properties =
                    dialAdasProperties("http://localhost:" + serverSocket.getLocalPort(), 5000, 5000);
            RestClient restClient = configuration.dialAdasRestClient(properties, OpenTelemetry.noop());

            String body = restClient
                    .post()
                    .uri("/v1/queries/execute")
                    .body("{}")
                    .retrieve()
                    .body(String.class);

            assertThat(body).isEqualTo("{}");
        }
    }

    private static void acceptAndStall(ServerSocket serverSocket, long stallMs) {
        try (Socket socket = serverSocket.accept()) {
            Thread.sleep(stallMs);
        } catch (IOException | InterruptedException ignored) {
            // best-effort test server teardown
        }
    }

    private static void acceptStallThenRespond(ServerSocket serverSocket, long stallMs) {
        try (Socket socket = serverSocket.accept()) {
            Thread.sleep(stallMs);
            String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
            try (OutputStream out = socket.getOutputStream()) {
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // best-effort test server teardown
        }
    }
}
