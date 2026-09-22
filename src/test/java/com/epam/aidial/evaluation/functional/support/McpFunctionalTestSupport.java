package com.epam.aidial.evaluation.functional.support;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds MCP Streamable HTTP clients (mirroring {@code McpToolInvoker}'s client construction) and
 * raw JSON-RPC helpers for functional tests running against the booted application under
 * {@code @PostgresFunctionalTests}.
 */
public class McpFunctionalTestSupport {

    private static final String CLIENT_NAME = "mcp-functional-test";
    private static final String CLIENT_VERSION = "1.0.0";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final String baseUrl;
    private final String mcpEndpoint;

    public McpFunctionalTestSupport(String baseUrl, String mcpEndpoint) {
        this.baseUrl = baseUrl;
        this.mcpEndpoint = mcpEndpoint;
    }

    /** Builds (but does not initialize) an MCP client that sends the given headers on every request. */
    public McpSyncClient client(Map<String, String> headers) {
        HttpClient.Builder httpClientBuilder =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT);

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(mcpEndpoint)
                .clientBuilder(httpClientBuilder)
                .httpRequestCustomizer((requestBuilder, _, _, _, _) -> {
                    headers.forEach(requestBuilder::header);
                    requestBuilder.timeout(REQUEST_TIMEOUT);
                })
                .build();

        return McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder(CLIENT_NAME, CLIENT_VERSION)
                        .build())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
    }

    public McpSchema.CallToolResult callTool(McpSyncClient client, String name, Map<String, Object> arguments) {
        return client.callTool(
                McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
    }

    /** Parses the tool result's single {@code TextContent} block as JSON. */
    public JsonNode readJson(McpSchema.CallToolResult result) {
        McpSchema.TextContent textContent =
                (McpSchema.TextContent) result.content().get(0);
        return JSON_MAPPER.readTree(textContent.text());
    }

    /**
     * Raw JSON-RPC POST against the MCP endpoint via {@link TestRestTemplate}, bypassing the MCP
     * Java SDK entirely. Used to assert HTTP-level status codes (401 unauthenticated, 404 disabled
     * server) that the SDK's blocking transport would otherwise surface only as an opaque
     * client-side exception.
     */
    public ResponseEntity<String> rawPost(
            TestRestTemplate restTemplate, Map<String, String> headers, String jsonRpcBody) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        headers.forEach(httpHeaders::set);
        HttpEntity<String> request = new HttpEntity<>(jsonRpcBody, httpHeaders);
        return restTemplate.exchange(baseUrl + mcpEndpoint, HttpMethod.POST, request, String.class);
    }

    /** A minimal JSON-RPC 2.0 {@code initialize} request body, for {@link #rawPost}. */
    public static String initializeRequestBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + ProtocolVersions.MCP_2025_06_18
                + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"" + CLIENT_NAME + "\",\"version\":\""
                + CLIENT_VERSION + "\"}}}";
    }

    /** A minimal JSON-RPC 2.0 {@code tools/call} request body naming a tool with empty arguments,
     * for {@link #rawPost} session-behaviour assertions that never expect the call to reach the tool. */
    public static String toolsCallRequestBody(String toolName) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + toolName
                + "\",\"arguments\":{}}}";
    }
}
