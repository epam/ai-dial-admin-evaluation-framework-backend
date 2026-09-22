package com.epam.aidial.evaluation.runner.client.mcp;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
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
import java.net.http.HttpRequest;
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
            String deploymentId,
            String toolName,
            Map<String, Object> arguments,
            CallerCredential credential,
            McpTransport transport) {
        McpSyncClient client = createClient(deploymentId, credential, transport);
        try {
            client.initialize();
            return client.callTool(
                    CallToolRequest.builder(toolName).arguments(arguments).build());
        } catch (McpInvocationException e) {
            throw e;
        } catch (Exception e) { // MCP SDK boundary: reactor block() and transport throw diverse exception types
            throw mapException(e);
        } finally {
            closeQuietly(client);
        }
    }

    public List<McpSchema.Tool> listTools(String deploymentId, CallerCredential credential, McpTransport transport) {
        McpSyncClient client = createClient(deploymentId, credential, transport);
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

    private McpSyncClient createClient(String deploymentId, CallerCredential credential, McpTransport transport) {
        if (transport == McpTransport.SSE) {
            return createSseClient(deploymentId, credential);
        }
        return createStreamableHttpClient(deploymentId, credential);
    }

    private McpSyncClient createStreamableHttpClient(String deploymentId, CallerCredential credential) {
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
                .httpRequestCustomizer((requestBuilder, _, _, _, _) -> applyCredentialHeader(requestBuilder, credential)
                        .timeout(Duration.ofMillis(configuration.getReadTimeoutMs())))
                .build();

        return McpClient.sync(transport)
                .clientInfo(Implementation.builder(CLIENT_NAME, CLIENT_VERSION).build())
                .capabilities(ClientCapabilities.builder().build())
                .jsonSchemaValidator(noOpSchemaValidator())
                .build();
    }

    private McpSyncClient createSseClient(String deploymentId, CallerCredential credential) {
        // HttpClientSseClientTransport applies connectTimeout via the builder (not the HttpClient.Builder),
        // because its build() method calls clientBuilder.connectTimeout(this.connectTimeout) itself.
        @SuppressWarnings("deprecation")
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(
                        configuration.getMcpProxyBaseUrl())
                .sseEndpoint(buildSseEndpoint(deploymentId))
                .connectTimeout(Duration.ofMillis(configuration.getConnectTimeoutMs()))
                .httpRequestCustomizer((requestBuilder, _, _, _, _) -> applyCredentialHeader(requestBuilder, credential)
                        .timeout(Duration.ofMillis(configuration.getReadTimeoutMs())))
                .build();

        return McpClient.sync(transport)
                .clientInfo(Implementation.builder(CLIENT_NAME, CLIENT_VERSION).build())
                .capabilities(ClientCapabilities.builder().build())
                .jsonSchemaValidator(noOpSchemaValidator())
                .build();
    }

    /**
     * Sets exactly one outbound header from {@code credential}'s kind ({@code Authorization: Bearer}
     * or {@link CallerCredential#API_KEY_HEADER}), or none at all when {@code credential} is
     * {@code null} — never {@code Authorization: Bearer null}.
     */
    static HttpRequest.Builder applyCredentialHeader(HttpRequest.Builder requestBuilder, CallerCredential credential) {
        if (credential == null) {
            return requestBuilder;
        }
        return requestBuilder.header(credential.headerName(), credential.headerValue());
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
