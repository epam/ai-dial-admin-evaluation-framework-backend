package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.utils.EvalBaggage;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Slice tests for {@link DialCoreClientConfiguration#tracingInterceptor(OpenTelemetry)} — the interceptor
 * that serializes the current OTel context (trace context + baggage) into outgoing headers. The
 * {@code @SpringBootTest} functional harness mocks the DIAL clients as {@code @MockitoBean} and bypasses
 * this interceptor, so a boot-context test cannot observe the header; these drive the interceptor directly
 * via {@link MockRestServiceServer}, following the pattern in {@code DialCoreClientTest}.
 *
 * <p>The metric-provider client ({@code MetricProviderRestClientConfiguration}) uses this SAME static
 * interceptor factory, so {@link #shouldSerializeBaggageForMetricProviderClient()} covers that path too.
 */
@DisplayName("tracingInterceptor baggage serialization")
class TracingInterceptorBaggageTest {

    /** An OpenTelemetry with the default W3C trace-context + baggage propagators (as when OTel is enabled). */
    private static OpenTelemetry propagatingOpenTelemetry() {
        return OpenTelemetry.propagating(ContextPropagators.create(TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())));
    }

    private static RestClient clientWith(OpenTelemetry openTelemetry, MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        return builder.requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
    }

    @Test
    @DisplayName("injects a baggage header carrying eval.run.id and eval.suite.id when OTel is enabled")
    void shouldInjectBaggageHeaderWhenOtelEnabled() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        RestClient client = clientWith(propagatingOpenTelemetry(), serverOut);

        serverOut[0]
                .expect(requestTo("/deployments/x/chat/completions"))
                .andExpect(request -> {
                    String baggage = request.getHeaders().getFirst("baggage");
                    assertThat(baggage).isNotNull();
                    assertThat(baggage).contains("eval.run.id=" + runId);
                    assertThat(baggage).contains("eval.suite.id=" + suiteId);
                })
                .andRespond(withSuccess());

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId)) {
            client.get().uri("/deployments/x/chat/completions").retrieve().toBodilessEntity();
        }

        serverOut[0].verify();
    }

    @Test
    @DisplayName("baggage header carries only the two eval id members — no authorization or api-key")
    void shouldCarryOnlyNonSensitiveIdentifiers() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        RestClient client = clientWith(propagatingOpenTelemetry(), serverOut);

        serverOut[0]
                .expect(requestTo("/x"))
                .andExpect(request -> {
                    String baggage = request.getHeaders().getFirst("baggage");
                    assertThat(baggage).isNotNull();
                    String[] members = baggage.split(",");
                    assertThat(members).hasSize(2);
                    assertThat(Arrays.stream(members).map(String::trim))
                            .allMatch(m -> m.startsWith("eval.run.id=") || m.startsWith("eval.suite.id="));
                    assertThat(baggage.toLowerCase()).doesNotContain("authorization");
                    assertThat(baggage.toLowerCase()).doesNotContain("api-key");
                })
                .andRespond(withSuccess());

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId)) {
            client.get().uri("/x").retrieve().toBodilessEntity();
        }

        serverOut[0].verify();
    }

    @Test
    @DisplayName("injects no baggage header when OTel is disabled (no-op propagator)")
    void shouldNotInjectBaggageHeaderWhenOtelDisabled() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        // OpenTelemetry.noop().getPropagators() == ContextPropagators.noop() -> inject is a no-op.
        RestClient client = clientWith(OpenTelemetry.noop(), serverOut);

        serverOut[0]
                .expect(requestTo("/x"))
                .andExpect(request ->
                        assertThat(request.getHeaders().getFirst("baggage")).isNull())
                .andRespond(withSuccess());

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId)) {
            client.get().uri("/x").retrieve().toBodilessEntity();
        }

        serverOut[0].verify();
    }

    @Test
    @DisplayName("serializes baggage for the metric-provider client (same tracingInterceptor factory)")
    void shouldSerializeBaggageForMetricProviderClient() {
        // MetricProviderRestClientConfiguration builds its clients with the identical
        // DialCoreClientConfiguration.tracingInterceptor(openTelemetry), so this asserts the metric path.
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        RestClient client = clientWith(propagatingOpenTelemetry(), serverOut);

        serverOut[0]
                .expect(requestTo("/evaluate"))
                .andExpect(request -> {
                    String baggage = request.getHeaders().getFirst("baggage");
                    assertThat(baggage).isNotNull();
                    assertThat(baggage).contains("eval.run.id=" + runId);
                    assertThat(baggage).contains("eval.suite.id=" + suiteId);
                })
                .andRespond(withSuccess());

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId)) {
            client.post().uri("/evaluate").retrieve().toBodilessEntity();
        }

        serverOut[0].verify();
    }
}
