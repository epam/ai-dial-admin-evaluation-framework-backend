package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DialCoreDeploymentInvoker")
class DialCoreDeploymentInvokerTest {

    private MockRestServiceServer server;
    private DialCoreDeploymentInvoker invoker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        DialCoreProperties properties = new DialCoreProperties();
        properties.setBaseUrl("http://localhost");
        invoker = new DialCoreDeploymentInvoker(restClient, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("successful JSON response is parsed")
    void successfulJsonResponseIsParsed() {
        String json = """
                {"id":"chatcmpl-1","choices":[{"message":{"content":"Hello!"}}]}
                """;
        server.expect(request ->
                        assertThat(request.getURI().getPath()).isEqualTo("/openai/deployments/gpt-4/chat/completions"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DeploymentInvocationResponse response = invoker.invoke(
                HttpMethod.POST,
                "/openai/deployments/gpt-4/chat/completions",
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of("prompt", "Hi"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.body();
        assertThat(body.get("id")).isEqualTo("chatcmpl-1");
        server.verify();
    }

    @Test
    @DisplayName("non-JSON response returns raw string")
    void nonJsonResponseReturnsRawString() {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/path"))
                .andRespond(withSuccess("<html>Error</html>", MediaType.TEXT_HTML));

        DeploymentInvocationResponse response = invoker.invoke(
                HttpMethod.POST, "/path", new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of("k", "v"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("<html>Error</html>");
        server.verify();
    }

    @Test
    @DisplayName("HTTP 4xx from Core returns status+body as-is")
    void http4xxReturnsAsIs() {
        String errorJson = """
                {"error":"Bad Request","message":"Invalid model"}
                """;
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/path"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson));

        DeploymentInvocationResponse response =
                invoker.invoke(HttpMethod.POST, "/path", new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isInstanceOf(Map.class);
        server.verify();
    }

    @Test
    @DisplayName("HTTP 5xx from Core returns status+body as-is")
    void http5xxReturnsAsIs() {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/path"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Internal Server Error"));

        DeploymentInvocationResponse response =
                invoker.invoke(HttpMethod.POST, "/path", new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("Internal Server Error");
        server.verify();
    }

    @Test
    @DisplayName("Content-Type set to application/json for POST with body")
    void contentTypeSetForPostWithBody() {
        server.expect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(content().string("{\"prompt\":\"Hi\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        invoker.invoke(
                HttpMethod.POST, "/path", new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of("prompt", "Hi"));

        server.verify();
    }

    @Test
    @DisplayName("body ignored for GET requests")
    void bodyIgnoredForGetRequests() {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        DeploymentInvocationResponse response = invoker.invoke(
                HttpMethod.GET,
                "/path",
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of("should-be", "ignored"));

        assertThat(response.statusCode()).isEqualTo(200);
        server.verify();
    }

    @Test
    @DisplayName("query parameters are appended to URL")
    void queryParamsAppendedToUrl() {
        server.expect(request -> {
                    assertThat(request.getURI().getQuery()).contains("model=gpt-4");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("model", "gpt-4");
        invoker.invoke(HttpMethod.POST, "/path", new HttpHeaders(), params, Map.of());

        server.verify();
    }

    @Test
    @DisplayName("custom headers are passed through")
    void customHeadersPassedThrough() {
        server.expect(header("X-Custom", "my-value")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom", "my-value");
        invoker.invoke(HttpMethod.POST, "/path", headers, new LinkedMultiValueMap<>(), Map.of());

        server.verify();
    }

    @Test
    @DisplayName("connection failure throws DialCoreClientException with 502")
    void connectionFailureThrows502() {
        server.expect(method(HttpMethod.POST)).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThatThrownBy(() -> invoker.invoke(
                        HttpMethod.POST, "/path", new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of()))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(ex -> {
                    DialCoreClientException dex = (DialCoreClientException) ex;
                    assertThat(dex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(dex.getMessage()).contains("Failed to connect");
                });
    }

    @Test
    @DisplayName("read timeout throws DialCoreClientException with 504")
    void readTimeoutThrows504() {
        server.expect(method(HttpMethod.POST)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertThatThrownBy(() -> invoker.invoke(
                        HttpMethod.POST, "/path", new HttpHeaders(), new LinkedMultiValueMap<>(), Map.of()))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(ex -> {
                    DialCoreClientException dex = (DialCoreClientException) ex;
                    assertThat(dex.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(dex.getMessage()).contains("did not respond within");
                });
    }
}
