package com.epam.aidial.evaluation.client.mcp;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import com.epam.aidial.evaluation.configuration.properties.dial.McpClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for MCP client. Provides access to MCP client properties
 * and DIAL Core base URL for constructing MCP proxy endpoints.
 */
@Configuration
@LogExecution
@RequiredArgsConstructor
public class McpClientConfiguration {

    private final McpClientProperties mcpClientProperties;
    private final DialCoreProperties dialCoreProperties;

    public String getMcpProxyBaseUrl() {
        return dialCoreProperties.getBaseUrl();
    }

    public int getConnectTimeoutMs() {
        return mcpClientProperties.getConnectTimeoutMs();
    }

    public int getReadTimeoutMs() {
        return mcpClientProperties.getReadTimeoutMs();
    }
}
