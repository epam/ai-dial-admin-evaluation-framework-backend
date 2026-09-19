package com.epam.aidial.evaluation.mcp.constants;

/**
 * {@code snake_case} names of the MCP server's tools, one constant per tool, shared between
 * {@code @McpTool(name = ...)} declarations and the functional test harness.
 */
public final class McpToolNames {

    public static final String LIST_DEPLOYMENTS = "list_deployments";
    public static final String GET_DEPLOYMENT = "get_deployment";

    private McpToolNames() {}
}
