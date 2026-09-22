package com.epam.aidial.evaluation.mcp.model;

import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Type-specific detail nested under {@link DeploymentMcpDto#toolset()} for
 * {@link DeploymentKind#DIAL_TOOLSET} deployments (D-F8).
 */
public record ToolsetDetailsMcpDto(
        @Nullable McpTransport transport, @Nullable List<String> allowedTools) {}
