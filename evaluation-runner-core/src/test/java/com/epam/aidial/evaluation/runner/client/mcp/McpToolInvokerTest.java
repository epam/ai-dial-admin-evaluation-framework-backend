package com.epam.aidial.evaluation.runner.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("McpToolInvoker")
class McpToolInvokerTest {

    private McpClientConfiguration config;
    private McpToolInvoker invoker;

    @BeforeEach
    void setUp() {
        config = mock(McpClientConfiguration.class);
        invoker = new McpToolInvoker(config);
    }

    @Nested
    @DisplayName("buildMcpEndpoint — deployment ID as path segments")
    class BuildMcpEndpointTest {

        @Test
        @DisplayName("Simple ID produces /v1/toolset/{id}/mcp endpoint path")
        void simpleIdProducesEndpointPath() {
            assertThat(invoker.buildMcpEndpoint("my-toolset")).isEqualTo("/v1/toolset/my-toolset/mcp");
        }

        @Test
        @DisplayName("Slash-separated ID produces multi-segment endpoint path")
        void slashSeparatedIdProducesMultiSegmentPath() {
            assertThat(invoker.buildMcpEndpoint("my-org/my-toolset")).isEqualTo("/v1/toolset/my-org/my-toolset/mcp");
        }

        @Test
        @DisplayName("URL-encoded slash (%2F) in ID is decoded to path separator")
        void urlEncodedSlashIsDecodedToPathSeparator() {
            assertThat(invoker.buildMcpEndpoint("my-org%2Fmy-toolset")).isEqualTo("/v1/toolset/my-org/my-toolset/mcp");
        }

        @Test
        @DisplayName("Deeply nested ID produces all segments in endpoint path")
        void deeplyNestedIdProducesAllSegments() {
            assertThat(invoker.buildMcpEndpoint("a/b/c")).isEqualTo("/v1/toolset/a/b/c/mcp");
        }

        @Test
        @DisplayName("ID with spaces is percent-encoded in the resulting path")
        void idWithSpacesIsPercentEncoded() {
            assertThat(invoker.buildMcpEndpoint("toolsets/public/27.03 deepwiki toolset__0.0.1"))
                    .isEqualTo("/v1/toolset/toolsets/public/27.03%20deepwiki%20toolset__0.0.1/mcp");
        }
    }

    @Nested
    @DisplayName("buildSseEndpoint — deployment ID as path segments")
    class BuildSseEndpointTest {

        @Test
        @DisplayName("Simple ID produces /v1/toolset/{id}/sse endpoint path")
        void simpleIdProducesSsePath() {
            assertThat(invoker.buildSseEndpoint("my-toolset")).isEqualTo("/v1/toolset/my-toolset/sse");
        }

        @Test
        @DisplayName("Slash-separated ID produces multi-segment SSE endpoint path")
        void slashSeparatedIdProducesMultiSegmentSsePath() {
            assertThat(invoker.buildSseEndpoint("my-org/my-toolset")).isEqualTo("/v1/toolset/my-org/my-toolset/sse");
        }

        @Test
        @DisplayName("ID with spaces is percent-encoded in SSE path")
        void idWithSpacesIsPercentEncodedInSsePath() {
            assertThat(invoker.buildSseEndpoint("toolsets/public/my tool"))
                    .isEqualTo("/v1/toolset/toolsets/public/my%20tool/sse");
        }
    }

    @Nested
    @DisplayName("exception mapping")
    class ExceptionMappingTest {

        @Test
        @DisplayName("HttpTimeoutException maps to 504 MCP_TIMEOUT")
        void httpTimeoutExceptionMapsTo504() {
            HttpTimeoutException cause = new HttpTimeoutException("read timed out");
            RuntimeException wrapper = new RuntimeException("transport error", cause);

            McpInvocationException result = invoker.mapException(wrapper);

            assertThat(result.getStatusCode()).isEqualTo(504);
            assertThat(result.getErrorCode()).isEqualTo("MCP_TIMEOUT");
            assertThat(result.getMessage()).contains("timed out");
        }

        @Test
        @DisplayName("Direct HttpTimeoutException maps to 504 MCP_TIMEOUT")
        void directHttpTimeoutExceptionMapsTo504() {
            HttpTimeoutException ex = new HttpTimeoutException("request timeout");

            McpInvocationException result = invoker.mapException(ex);

            assertThat(result.getStatusCode()).isEqualTo(504);
            assertThat(result.getErrorCode()).isEqualTo("MCP_TIMEOUT");
        }

        @Test
        @DisplayName("Exception with Timeout in class name maps to 504")
        void timeoutClassNameMapsTo504() {
            Exception ex = new SocketTimeoutTestException("socket timeout");

            McpInvocationException result = invoker.mapException(ex);

            assertThat(result.getStatusCode()).isEqualTo(504);
            assertThat(result.getErrorCode()).isEqualTo("MCP_TIMEOUT");
        }

        @Test
        @DisplayName("ConnectException maps to 502 MCP_CONNECTION_ERROR")
        void connectExceptionMapsTo502() {
            ConnectException cause = new ConnectException("Connection refused");
            RuntimeException wrapper = new RuntimeException("transport error", cause);

            McpInvocationException result = invoker.mapException(wrapper);

            assertThat(result.getStatusCode()).isEqualTo(502);
            assertThat(result.getErrorCode()).isEqualTo("MCP_CONNECTION_ERROR");
        }

        @Test
        @DisplayName("IOException with 'Connection refused' message maps to 502")
        void ioExceptionConnectionRefusedMapsTo502() {
            IOException cause = new IOException("Connection refused (no route to host)");
            RuntimeException wrapper = new RuntimeException("transport error", cause);

            McpInvocationException result = invoker.mapException(wrapper);

            assertThat(result.getStatusCode()).isEqualTo(502);
            assertThat(result.getErrorCode()).isEqualTo("MCP_CONNECTION_ERROR");
        }

        @Test
        @DisplayName("IOException with DNS message maps to 502")
        void ioExceptionDnsMapsTo502() {
            IOException cause = new IOException("DNS resolution failed");
            RuntimeException wrapper = new RuntimeException("transport error", cause);

            McpInvocationException result = invoker.mapException(wrapper);

            assertThat(result.getStatusCode()).isEqualTo(502);
            assertThat(result.getErrorCode()).isEqualTo("MCP_CONNECTION_ERROR");
        }

        @Test
        @DisplayName("Generic RuntimeException maps to 502 MCP_ERROR")
        void genericExceptionMapsTo502() {
            RuntimeException ex = new RuntimeException("JSON-RPC parse error");

            McpInvocationException result = invoker.mapException(ex);

            assertThat(result.getStatusCode()).isEqualTo(502);
            assertThat(result.getErrorCode()).isEqualTo("MCP_ERROR");
            assertThat(result.getMessage()).contains("JSON-RPC parse error");
        }

        @Test
        @DisplayName("Exception with null message maps to 502 with fallback message")
        void nullMessageMapsTo502WithFallback() {
            RuntimeException ex = new RuntimeException((String) null);

            McpInvocationException result = invoker.mapException(ex);

            assertThat(result.getStatusCode()).isEqualTo(502);
            assertThat(result.getMessage()).contains("MCP invocation failed");
        }

        @Test
        @DisplayName("Deeply nested timeout exception is detected")
        void deeplyNestedTimeoutDetected() {
            HttpTimeoutException timeout = new HttpTimeoutException("timed out");
            IOException ioWrap = new IOException("IO error", timeout);
            RuntimeException outerWrap = new RuntimeException("outer", ioWrap);

            McpInvocationException result = invoker.mapException(outerWrap);

            assertThat(result.getStatusCode()).isEqualTo(504);
            assertThat(result.getErrorCode()).isEqualTo("MCP_TIMEOUT");
        }

        /**
         * Fake exception whose class name contains "Timeout" — exercises the class-name check path.
         */
        private static class SocketTimeoutTestException extends Exception {
            SocketTimeoutTestException(String message) {
                super(message);
            }
        }
    }
}
