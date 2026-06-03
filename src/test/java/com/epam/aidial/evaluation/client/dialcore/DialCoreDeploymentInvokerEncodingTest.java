package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Verifies that {@link DialCoreDeploymentInvoker} encodes the wire path exactly once,
 * regardless of whether the input id arrives pre-encoded (the form DIAL Core returns)
 * or with literal special characters.
 *
 * <p>The {@code RestClient} is wired the same shape that production uses (see
 * {@link DialCoreDeploymentInvokerConfiguration#dialCoreTryOutRestClient}): a
 * {@code baseUrl} plus Spring's default {@code DefaultUriBuilderFactory} in
 * {@code TEMPLATE_AND_VALUES} mode. {@link MockRestServiceServer} swaps only the request
 * factory, leaving the URI-builder pipeline untouched, so the captured
 * {@code request.getURI().getRawPath()} is the exact byte sequence that would land on the
 * wire. Asserting on intermediate {@code UriComponentsBuilder} output would bypass that
 * pipeline and could mask the original double-encoding bug.
 */
@DisplayName("DialCoreDeploymentInvoker — wire-path encoding")
class DialCoreDeploymentInvokerEncodingTest {

    private static final String BASE_URL = "http://core.example";

    private MockRestServiceServer server;
    private DialCoreDeploymentInvoker invoker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        DialCoreProperties properties = new DialCoreProperties();
        properties.setBaseUrl(BASE_URL);
        invoker = new DialCoreDeploymentInvoker(restClient, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("application id with spaces is encoded exactly once on the wire")
    void encodesSpacesExactlyOnceOnTheWire() {
        String path = "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions";

        server.expect(request -> {
                    String rawPath = request.getURI().getRawPath();
                    assertThat(rawPath)
                            .isEqualTo(
                                    "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions");
                    assertThat(rawPath).doesNotContain("%2520");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        invoker.invoke(HttpMethod.POST, path, new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of("prompt", "hi"));

        server.verify();
    }

    @Test
    @DisplayName("application id with parentheses preserves () literally per RFC 3986 sub-delims")
    void parenthesesPreservedLiterally() {
        String path = "/v1/deployments/applications/public/Quick%20App%20(v2)__0.0.1/route/chat/completions";

        server.expect(request -> {
                    String rawPath = request.getURI().getRawPath();
                    assertThat(rawPath)
                            .isEqualTo(
                                    "/v1/deployments/applications/public/Quick%20App%20(v2)__0.0.1/route/chat/completions");
                    assertThat(rawPath).doesNotContain("%2520");
                    assertThat(rawPath).doesNotContain("%2528");
                    assertThat(rawPath).doesNotContain("%2529");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        invoker.invoke(HttpMethod.POST, path, new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of());

        server.verify();
    }

    @Test
    @DisplayName("model id with no special characters passes through unchanged (OPENAI standard path regression guard)")
    void modelIdPassthrough() {
        String path = "/openai/deployments/gpt-4/chat/completions";

        server.expect(request -> assertThat(request.getURI().getRawPath())
                        .isEqualTo("/openai/deployments/gpt-4/chat/completions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        invoker.invoke(HttpMethod.POST, path, new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of());

        server.verify();
    }

    @ParameterizedTest(name = "idempotent: input \"{0}\" yields same single-encoded wire path")
    @ValueSource(
            strings = {
                "/v1/deployments/applications/public/Quick App with RAG__0.0.1/route/chat/completions",
                "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions"
            })
    void idempotentAcrossRawAndPreEncodedInputs(String inputPath) {
        server.expect(
                        request -> assertThat(request.getURI().getRawPath())
                                .isEqualTo(
                                        "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        invoker.invoke(HttpMethod.POST, inputPath, new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of());

        server.verify();
    }

    @Test
    @DisplayName("query parameters remain single-encoded alongside an encoded path")
    void queryParametersRemainSingleEncoded() {
        String path = "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions";

        server.expect(request -> {
                    String rawPath = request.getURI().getRawPath();
                    String rawQuery = request.getURI().getRawQuery();
                    assertThat(rawPath)
                            .isEqualTo(
                                    "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions");
                    assertThat(rawPath).doesNotContain("%2520");
                    assertThat(rawQuery).isEqualTo("model=gpt-4");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("model", "gpt-4");

        invoker.invoke(HttpMethod.POST, path, new HttpHeaders(), params, Map.of());

        server.verify();
    }
}
