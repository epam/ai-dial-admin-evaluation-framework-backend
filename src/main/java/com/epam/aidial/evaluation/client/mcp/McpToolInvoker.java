package com.epam.aidial.evaluation.client.mcp;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class McpToolInvoker {

    private static final String CLIENT_NAME = "evaluation-framework";
    private static final String CLIENT_VERSION = "1.0.0";

    private final McpClientConfiguration configuration;

    public CallToolResult callTool(
            String deploymentId, String toolName, Map<String, Object> arguments, String token, McpTransport transport) {
        McpSyncClient client = createClient(deploymentId, token, transport);
        try {
            client.initialize();
            return client.callTool(new CallToolRequest(toolName, arguments));
        } catch (McpInvocationException e) {
            throw e;
        } catch (Exception e) { // MCP SDK boundary: reactor block() and transport throw diverse exception types
            throw mapException(e);
        } finally {
            closeQuietly(client);
        }
    }

    public List<McpSchema.Tool> listTools(String deploymentId, String token, McpTransport transport) {
        McpSyncClient client = createClient(deploymentId, token, transport);
        try {
            client.initialize();
            return client.listTools().tools();
        } catch (McpInvocationException e) {
            throw e;
        } catch (Exception e) { // MCP SDK boundary: reactor block() and transport throw diverse exception types
            throw mapException(e);
        } finally {
            closeQuietly(client);
        }
    }

    private McpSyncClient createClient(String deploymentId, String token, McpTransport transport) {
        if (transport == McpTransport.SSE) {
            return createSseClient(deploymentId, token);
        }
        return createStreamableHttpClient(deploymentId, token);
    }

    private McpSyncClient createStreamableHttpClient(String deploymentId, String token) {
        // HttpClientStreamableHttpTransport resolves the endpoint against the base URI using
        // standard URI resolution. The base must be the DIAL Core root URL, and the endpoint
        // must be the absolute path — otherwise Java URI.resolve() discards the toolset path.
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(configuration.getConnectTimeoutMs()));

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        configuration.getMcpProxyBaseUrl())
                .endpoint(buildMcpEndpoint(deploymentId))
                .clientBuilder(httpClientBuilder)
                .customizeRequest(requestBuilder -> requestBuilder
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofMillis(configuration.getReadTimeoutMs())))
                .build();

        return McpClient.sync(transport)
                .clientInfo(new Implementation(CLIENT_NAME, CLIENT_VERSION))
                .capabilities(ClientCapabilities.builder().build())
                .jsonSchemaValidator(noOpSchemaValidator())
                .build();
    }

    private McpSyncClient createSseClient(String deploymentId, String token) {
        // HttpClientSseClientTransport applies connectTimeout via the builder (not the HttpClient.Builder),
        // because its build() method calls clientBuilder.connectTimeout(this.connectTimeout) itself.
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(
                        configuration.getMcpProxyBaseUrl())
                .sseEndpoint(buildSseEndpoint(deploymentId))
                .connectTimeout(Duration.ofMillis(configuration.getConnectTimeoutMs()))
                .customizeRequest(requestBuilder -> requestBuilder
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofMillis(configuration.getReadTimeoutMs())))
                .build();

        return McpClient.sync(transport)
                .clientInfo(new Implementation(CLIENT_NAME, CLIENT_VERSION))
                .capabilities(ClientCapabilities.builder().build())
                .jsonSchemaValidator(noOpSchemaValidator())
                .build();
    }

    String buildMcpEndpoint(String deploymentId) {
        // Returns the absolute path /v1/toolset/{segments}/mcp.
        // Decode the ID first (client may send %2F-encoded slashes), then split into
        // separate path segments so they are forwarded as path separators, not %2F.
        String[] idSegments =
                UriUtils.decode(deploymentId, StandardCharsets.UTF_8).split("/");
        return UriComponentsBuilder.newInstance()
                .pathSegment("v1", "toolset")
                .pathSegment(idSegments)
                .pathSegment("mcp")
                .build()
                .encode()
                .toUriString();
    }

    String buildSseEndpoint(String deploymentId) {
        // Returns the absolute path /v1/toolset/{segments}/sse for legacy SSE transport.
        String[] idSegments =
                UriUtils.decode(deploymentId, StandardCharsets.UTF_8).split("/");
        return UriComponentsBuilder.newInstance()
                .pathSegment("v1", "toolset")
                .pathSegment(idSegments)
                .pathSegment("sse")
                .build()
                .encode()
                .toUriString();
    }

    private static JsonSchemaValidator noOpSchemaValidator() {
        // MCP SDK schema validation is only used when enableCallToolSchemaCaching=true (off by default).
        // Providing a no-op validator prevents DefaultJsonSchemaValidator from being loaded,
        // which requires networknt json-schema-validator 2.x while the classpath has 1.x.
        return (schema, json) -> JsonSchemaValidator.ValidationResponse.asValid(json.toString());
    }

    McpInvocationException mapException(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : "MCP invocation failed";

        if (isTimeout(e)) {
            return new McpInvocationException(504, "MCP_TIMEOUT", "MCP tool invocation timed out: " + message, e);
        }

        if (isConnectionError(e)) {
            return new McpInvocationException(
                    502, "MCP_CONNECTION_ERROR", "Failed to connect to MCP endpoint: " + message, e);
        }

        return new McpInvocationException(502, "MCP_ERROR", "MCP tool invocation failed: " + message, e);
    }

    private static boolean isTimeout(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return true;
            }
            String name = current.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isConnectionError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            if (current instanceof IOException && !(current instanceof HttpTimeoutException)) {
                String msg = current.getMessage();
                if (msg != null
                        && (msg.contains("Connection refused")
                                || msg.contains("No route to host")
                                || msg.contains("DNS"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Closes the MCP client (and underlying transport + HttpClient) quietly.
     * The transport's closeGracefully() handles HttpClient lifecycle.
     */
    private static void closeQuietly(McpSyncClient client) {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.warn("Error closing MCP client: {}", e.getMessage(), e);
        }
    }
}
