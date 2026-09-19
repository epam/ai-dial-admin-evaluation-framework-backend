package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreCapabilitiesDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreLimitsDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialTransport;
import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.functional.support.CallerIdentityProbeTools;
import com.epam.aidial.evaluation.functional.support.McpFunctionalTestSupport;
import com.epam.aidial.evaluation.mcp.constants.McpToolNames;
import com.epam.aidial.evaluation.mcp.model.DeploymentInterface;
import com.epam.aidial.evaluation.mcp.model.DeploymentKind;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

/**
 * Foundation-level MCP server functional tests (security mode: none): the endpoint itself,
 * server metadata, the {@code tools/list} contract, the D-F2 request-thread caller identity
 * verification via the test-only probe tool, and the deployments tool group (D-F8) end to end
 * against a mocked {@link DialCoreClient}.
 *
 * <p>{@code @Import(CallerIdentityProbeTools.class)} is declared on the concrete nested
 * registration in {@code PostgresFunctionalTests}, not here, matching the established pattern of
 * putting per-scenario test configuration on the nested class rather than the abstract base.
 */
@DisplayName("MCP server foundation (security mode: none)")
public abstract class McpServerFoundationFunctionalTests extends BaseFunctionalTest {

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String mcpEndpoint;

    @Value("${spring.ai.mcp.server.name}")
    private String configuredServerName;

    @Autowired
    private DialCoreClient dialCoreClient;

    private McpSyncClient client;

    @BeforeEach
    void resetDialCoreClientMock() {
        reset(dialCoreClient);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
    }

    private McpFunctionalTestSupport support() {
        return new McpFunctionalTestSupport(baseUrl(), mcpEndpoint);
    }

    @Test
    @DisplayName("initialize succeeds and the server instructions describe the try_out / get_run workflow")
    void initializeSucceedsAndInstructionsDescribeWorkflow() {
        client = support().client(Map.of());

        McpSchema.InitializeResult result = client.initialize();

        assertThat(result).isNotNull();
        String instructions = client.getServerInstructions();
        assertThat(instructions).isNotNull().contains("try_out").contains("get_run");
    }

    @Test
    @DisplayName("initialize advertises only the tools capability and identifies the server by its "
            + "configured name and a non-blank version")
    void initializeAdvertisesToolsCapabilityOnlyAndServerIdentity() {
        client = support().client(Map.of());

        McpSchema.InitializeResult result = client.initialize();

        assertThat(result.capabilities().tools()).isNotNull();
        assertThat(result.capabilities().resources()).isNull();
        assertThat(result.capabilities().prompts()).isNull();
        assertThat(result.serverInfo().name()).isEqualTo(configuredServerName);
        assertThat(result.serverInfo().version()).isNotBlank();
    }

    @Test
    @DisplayName(
            "probe_caller_identity reports the anonymous, unauthenticated caller with no credentialKind in none mode")
    void probeReportsAnonymousUnauthenticatedCallerInNoneMode() {
        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();

        McpSchema.CallToolResult result = support.callTool(client, CallerIdentityProbeTools.PROBE_TOOL_NAME, Map.of());
        var probe = support.readJson(result);

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(probe.get("createdBy").asString()).isEqualTo("anonymous");
        assertThat(probe.get("authenticationPresent").asBoolean()).isFalse();
        assertThat(probe.get("credentialKind")).isNull();
    }

    // --- session behaviour (task 4.4) ---

    @Test
    @DisplayName("a second initialize on a fresh client yields a different Mcp-Session-Id than the first")
    void secondInitializeOnFreshClientYieldsDifferentSessionId() {
        McpFunctionalTestSupport support = support();

        ResponseEntity<String> first =
                support.rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());
        ResponseEntity<String> second =
                support.rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());

        String firstSessionId = first.getHeaders().getFirst("Mcp-Session-Id");
        String secondSessionId = second.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(firstSessionId).isNotBlank();
        assertThat(secondSessionId).isNotBlank();
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);
    }

    @Test
    @DisplayName("a tools/call with an unknown Mcp-Session-Id is rejected by the transport without reaching "
            + "DialCoreClient")
    void toolsCallWithUnknownSessionIdIsRejectedWithoutReachingDialCoreClient() {
        ResponseEntity<String> response = support()
                .rawPost(
                        restTemplate,
                        Map.of("Mcp-Session-Id", "00000000-0000-0000-0000-000000000000"),
                        McpFunctionalTestSupport.toolsCallRequestBody(McpToolNames.LIST_DEPLOYMENTS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(dialCoreClient);
    }

    // --- tools/list contract (D-F10 / task 3.5) ---

    @Test
    @DisplayName("tools/list production tool set is exactly {list_deployments, get_deployment}")
    void toolsListProductionToolNamesAreExactlyDeploymentTools() {
        client = support().client(Map.of());
        client.initialize();

        List<String> productionToolNames =
                productionTools(client).stream().map(McpSchema.Tool::name).toList();

        assertThat(productionToolNames)
                .containsExactlyInAnyOrder(McpToolNames.LIST_DEPLOYMENTS, McpToolNames.GET_DEPLOYMENT);
    }

    @Test
    @DisplayName("tools/list descriptions are non-empty and every input property has a non-blank description")
    void toolsListDescriptionsAreNonEmptyAndEveryPropertyDescriptionIsNonBlank() {
        client = support().client(Map.of());
        client.initialize();

        List<McpSchema.Tool> productionToolList = productionTools(client);

        assertThat(productionToolList).isNotEmpty();
        for (McpSchema.Tool tool : productionToolList) {
            assertThat(tool.description()).isNotBlank();
            for (Object propertyValue : properties(tool).values()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> property = (Map<String, Object>) propertyValue;
                assertThat((String) property.get("description")).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("list_deployments' type and interfaceType are optional string filters whose descriptions "
            + "contain every accepted value")
    void listDeploymentsFilterPropertiesAreOptionalStringsDescribingAcceptedValues() {
        client = support().client(Map.of());
        client.initialize();

        McpSchema.Tool listDeployments = toolNamed(client, McpToolNames.LIST_DEPLOYMENTS);
        Map<String, Object> properties = properties(listDeployments);

        assertThat(required(listDeployments)).doesNotContain("type", "interfaceType");
        assertPropertyIsOptionalString(properties, "type");
        assertPropertyIsOptionalString(properties, "interfaceType");
        for (DeploymentKind kind : DeploymentKind.values()) {
            assertThat(propertyDescription(properties, "type")).contains(kind.getWireValue());
        }
        for (DeploymentInterface interfaceValue : DeploymentInterface.values()) {
            assertThat(propertyDescription(properties, "interfaceType")).contains(interfaceValue.getWireValue());
        }
    }

    @Test
    @DisplayName("get_deployment's deploymentId is required")
    void getDeploymentDeploymentIdIsRequired() {
        client = support().client(Map.of());
        client.initialize();

        McpSchema.Tool getDeployment = toolNamed(client, McpToolNames.GET_DEPLOYMENT);

        assertThat(required(getDeployment)).containsExactly("deploymentId");
    }

    // --- list_deployments (task 3.4) ---

    @Test
    @DisplayName("list_deployments with no filters returns a summary for every deployment, without detail fields")
    void listDeploymentsUnfilteredReturnsSummaryShapeForEveryDeployment() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(
                        DialCoreModelDto.builder()
                                .id("m1")
                                .displayName("Model 1")
                                .owner("org")
                                .createdAt(1L)
                                .updatedAt(2L)
                                .interfaces(List.of(InterfaceType.CHAT))
                                .build(),
                        DialCoreToolsetDto.builder()
                                .id("t1")
                                .displayName("Toolset 1")
                                .transport(DialTransport.HTTP)
                                .interfaces(List.of(InterfaceType.MCP))
                                .build(),
                        DialCoreApplicationDto.builder()
                                .id("a1")
                                .displayName("App 1")
                                .interfaces(List.of(InterfaceType.CHAT))
                                .build()));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result = support.callTool(client, McpToolNames.LIST_DEPLOYMENTS, Map.of());
        var payload = support.readJson(result);

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(payload.get("total").asInt()).isEqualTo(3);
        var deployments = payload.get("deployments");
        assertThat(deployments.size()).isEqualTo(3);
        var model = deployments.get(0);
        assertThat(model.get("deploymentId").asString()).isEqualTo("m1");
        assertThat(model.get("type").asString()).isEqualTo("dial-model");
        assertThat(model.has("model")).isFalse();
        assertThat(model.has("owner")).isFalse();
        assertThat(model.has("version")).isFalse();
        assertThat(model.has("createdAt")).isFalse();
        assertThat(model.has("updatedAt")).isFalse();
        var toolset = deployments.get(1);
        assertThat(toolset.get("type").asString()).isEqualTo("dial-toolset");
        assertThat(toolset.get("toolsetTransport").asString()).isEqualTo("streamable-http");
        var application = deployments.get(2);
        assertThat(application.get("type").asString()).isEqualTo("dial-application");
        for (var summary : deployments) {
            assertThat(summary.has("interfaces")).isTrue();
        }
    }

    @Test
    @DisplayName("list_deployments with type=dial-toolset returns only toolsets")
    void listDeploymentsFiltersByType() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(
                        DialCoreModelDto.builder()
                                .id("m1")
                                .displayName("Model 1")
                                .build(),
                        DialCoreToolsetDto.builder()
                                .id("t1")
                                .displayName("Toolset 1")
                                .build()));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.LIST_DEPLOYMENTS, Map.of("type", "dial-toolset"));
        var payload = support.readJson(result);

        assertThat(payload.get("total").asInt()).isEqualTo(1);
        assertThat(payload.get("deployments").get(0).get("type").asString()).isEqualTo("dial-toolset");
    }

    @Test
    @DisplayName("list_deployments with interfaceType=mcp passes the interface filter to DIAL Core")
    void listDeploymentsFiltersByInterfaceType() {
        when(dialCoreClient.getDeployments(eq("mcp")))
                .thenReturn(List.of(DialCoreToolsetDto.builder()
                        .id("t1")
                        .displayName("Toolset 1")
                        .build()));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.LIST_DEPLOYMENTS, Map.of("interfaceType", "mcp"));
        var payload = support.readJson(result);

        assertThat(payload.get("total").asInt()).isEqualTo(1);
        assertThat(payload.get("deployments").get(0).get("deploymentId").asString())
                .isEqualTo("t1");
        verify(dialCoreClient).getDeployments(eq("mcp"));
    }

    @Test
    @DisplayName("list_deployments with an invalid type returns VALIDATION_ERROR listing accepted values, "
            + "without calling DIAL Core")
    void listDeploymentsInvalidTypeReturnsValidationErrorAndSkipsUpstreamCall() {
        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();

        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.LIST_DEPLOYMENTS, Map.of("type", "model"));
        var error = support.readJson(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.get("message").asString())
                .contains("dial-model")
                .contains("dial-application")
                .contains("dial-toolset");
        verifyNoInteractions(dialCoreClient);
    }

    // --- get_deployment (task 3.4) ---

    @Test
    @DisplayName("get_deployment for a model id returns model detail with limits and capabilities")
    void getDeploymentReturnsModelDetail() {
        when(dialCoreClient.getDeploymentById(eq("gpt-5-mini")))
                .thenReturn(DialCoreModelDto.builder()
                        .id("gpt-5-mini")
                        .displayName("GPT-5 mini")
                        .capabilities(DialCoreCapabilitiesDto.builder()
                                .chatCompletion(true)
                                .build())
                        .limits(DialCoreLimitsDto.builder()
                                .maxTotalTokens(32000)
                                .maxCompletionTokens(4096)
                                .build())
                        .build());

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "gpt-5-mini"));
        var payload = support.readJson(result);

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(payload.get("deploymentId").asString()).isEqualTo("gpt-5-mini");
        assertThat(payload.get("type").asString()).isEqualTo("dial-model");
        assertThat(payload.get("model").get("limits").get("maxTotalTokens").asInt())
                .isEqualTo(32000);
        assertThat(payload.get("model")
                        .get("capabilities")
                        .get("chatCompletion")
                        .asBoolean())
                .isTrue();
        assertThat(payload.has("toolset")).isFalse();
        assertThat(payload.has("application")).isFalse();
    }

    @Test
    @DisplayName("get_deployment for a toolset id returns toolset detail with transport and allowedTools")
    void getDeploymentReturnsToolsetDetailWithAllowedTools() {
        when(dialCoreClient.getDeploymentById(eq("my-toolset")))
                .thenReturn(DialCoreToolsetDto.builder()
                        .id("my-toolset")
                        .displayName("My Toolset")
                        .transport(DialTransport.HTTP)
                        .allowedTools(List.of("search", "calculate"))
                        .build());

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "my-toolset"));
        var payload = support.readJson(result);

        assertThat(payload.get("type").asString()).isEqualTo("dial-toolset");
        assertThat(payload.get("toolset").get("transport").asString()).isEqualTo("streamable-http");
        assertThat(payload.get("toolset").get("allowedTools").size()).isEqualTo(2);
        assertThat(payload.has("model")).isFalse();
        assertThat(payload.has("application")).isFalse();
    }

    @Test
    @DisplayName("get_deployment for an application id returns application detail without routes")
    void getDeploymentReturnsApplicationDetailWithoutRoutes() {
        when(dialCoreClient.getDeploymentById(eq("EntityExtractor")))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id("EntityExtractor")
                        .displayName("Entity Extractor")
                        .applicationTypeSchemaId("schema-1")
                        .build());

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "EntityExtractor"));
        var payload = support.readJson(result);

        assertThat(payload.get("type").asString()).isEqualTo("dial-application");
        assertThat(payload.get("application").get("applicationTypeSchemaId").asString())
                .isEqualTo("schema-1");
        assertThat(payload.get("application").has("routes")).isFalse();
        assertThat(payload.has("model")).isFalse();
        assertThat(payload.has("toolset")).isFalse();
    }

    @Test
    @DisplayName("get_deployment for an id DIAL Core does not know returns NOT_FOUND naming the id")
    void getDeploymentUnknownIdReturnsNotFoundNamingId() {
        when(dialCoreClient.getDeploymentById(eq("missing-id")))
                .thenThrow(new DialCoreClientException(HttpStatus.NOT_FOUND, "not found"));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "missing-id"));
        var error = support.readJson(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.get("message").asString()).contains("missing-id");
    }

    @Test
    @DisplayName("get_deployment with a blank deploymentId returns VALIDATION_ERROR without calling DIAL Core")
    void getDeploymentBlankIdentifierReturnsValidationErrorAndSkipsUpstreamCall() {
        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();

        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "   "));
        var error = support.readJson(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(dialCoreClient);
    }

    @Test
    @DisplayName("get_deployment returns UPSTREAM_TIMEOUT when DIAL Core answers with a gateway timeout")
    void getDeploymentUpstreamGatewayTimeoutReturnsUpstreamTimeout() {
        when(dialCoreClient.getDeploymentById(eq("slow-id")))
                .thenThrow(new DialCoreClientException(HttpStatus.GATEWAY_TIMEOUT, "DIAL Core timed out"));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "slow-id"));
        var error = support.readJson(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.get("code").asString()).isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test
    @DisplayName(
            "get_deployment on a ResourceAccessException returns a message that never contains the " + "DIAL Core URL")
    void getDeploymentResourceAccessExceptionMessageNeverContainsUrl() {
        when(dialCoreClient.getDeploymentById(eq("unreachable-id")))
                .thenThrow(new ResourceAccessException(
                        "I/O error on GET request for \"http://core.internal/v1/deployments/unreachable-id\": "
                                + "Connection refused",
                        new ConnectException("Connection refused")));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "unreachable-id"));
        var error = support.readJson(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.get("code").asString()).isEqualTo("UPSTREAM_ERROR");
        assertThat(error.get("message").asString()).doesNotContain("http://");
    }

    @Test
    @DisplayName("get_deployment on an unexpected RuntimeException from DIAL Core returns INTERNAL_ERROR with "
            + "the generic message")
    void getDeploymentUnexpectedRuntimeExceptionReturnsInternalErrorGenericMessage() {
        when(dialCoreClient.getDeploymentById(eq("boom-id"))).thenThrow(new RuntimeException("some internal detail"));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "boom-id"));
        var error = support.readJson(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.get("message").asString()).isEqualTo("Unexpected server error");
    }

    // --- tools/list helpers ---

    private static List<McpSchema.Tool> productionTools(McpSyncClient client) {
        return client.listTools().tools().stream()
                .filter(tool -> !tool.name().startsWith("probe_"))
                .toList();
    }

    private static McpSchema.Tool toolNamed(McpSyncClient client, String name) {
        return productionTools(client).stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(McpSchema.Tool tool) {
        Object required = tool.inputSchema().get("required");
        return required == null ? List.of() : (List<String>) required;
    }

    @SuppressWarnings("unchecked")
    private static String propertyDescription(Map<String, Object> properties, String name) {
        return (String) ((Map<String, Object>) properties.get(name)).get("description");
    }

    @SuppressWarnings("unchecked")
    private static void assertPropertyIsOptionalString(Map<String, Object> properties, String name) {
        Map<String, Object> property = (Map<String, Object>) properties.get(name);
        assertThat(property).isNotNull();
        assertThat(property.get("type")).isEqualTo("string");
        assertThat((String) property.get("description")).isNotBlank();
    }
}
