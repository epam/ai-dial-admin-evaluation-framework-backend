package com.epam.aidial.evaluation.mcp.constants;

/**
 * Tool and parameter descriptions for the MCP server's `@McpTool`/`@McpToolParam` declarations
 * (D-F7), plus the shared unsupported-features sentence (D-F6) used by
 * {@code UnsupportedFeatureException}. Descriptions state preconditions, the accepted values of
 * string-typed filters, and the tool an agent is expected to call next.
 */
public final class McpToolDescriptions {

    /**
     * Literal sentence required by the umbrella spec's "Forward-compatible models with explicit
     * unsupported features" requirement (`add-mcp-server/specs/mcp-server/spec.md`). Any tool
     * description stating this version's single-request, single-turn DEPLOYMENT-suite limitation
     * MUST reuse this constant verbatim; {@code UnsupportedFeatureException} messages start with
     * it.
     */
    public static final String UNSUPPORTED_FEATURES_SENTENCE =
            "Not supported in this version: only single-request, single-turn DEPLOYMENT suites can be created and run.";

    /**
     * Description of the {@code type} filter parameter of {@code list_deployments}. Lists every
     * accepted {@code DeploymentKind} wire value.
     */
    public static final String DEPLOYMENT_TYPE_FILTER =
            "Optional filter on deployment type. Accepted values: dial-model, dial-application, "
                    + "dial-toolset. When omitted, deployments of every type are returned.";

    /**
     * Description of the {@code interfaceType} filter parameter of {@code list_deployments}.
     * Lists every wire value of {@code com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType}.
     */
    public static final String INTERFACE_TYPE_FILTER = "Optional filter on the invocation interface the "
            + "deployment exposes. Accepted values: chat, embedding, mcp, custom_ui, openaiChatCompletions, "
            + "openaiResponses, openaiEmbeddings, anthropicMessages. When omitted, deployments exposing any "
            + "interface are returned.";

    /** Description of the required {@code deploymentId} parameter of {@code get_deployment}. */
    public static final String DEPLOYMENT_ID =
            "The deploymentId of the deployment to inspect, as returned by list_deployments. Must not be blank.";

    /** Description of the {@code list_deployments} tool. */
    public static final String LIST_DEPLOYMENTS = "Lists the DIAL deployments visible to the caller in DIAL Core, "
            + "optionally filtered by type and/or invocation interface. Call get_deployment on a candidate to see "
            + "its full detail before using it in create_test_suite. Prefer deployments exposing a chat or "
            + "completions interface for DEPLOYMENT suites.";

    /** Description of the {@code get_deployment} tool. */
    public static final String GET_DEPLOYMENT =
            "Returns the full detail of one deployment by id, including its type-specific detail (model limits "
                    + "and capabilities, toolset transport and allowed tools, or application schema id), so the "
                    + "agent can decide which request template to use in create_test_suite.";

    /** Human-readable title of the {@code list_deployments} tool's {@code @McpTool.McpAnnotations}. */
    public static final String LIST_DEPLOYMENTS_TITLE = "List deployments";

    /** Human-readable title of the {@code get_deployment} tool's {@code @McpTool.McpAnnotations}. */
    public static final String GET_DEPLOYMENT_TITLE = "Get deployment";

    private McpToolDescriptions() {}
}
