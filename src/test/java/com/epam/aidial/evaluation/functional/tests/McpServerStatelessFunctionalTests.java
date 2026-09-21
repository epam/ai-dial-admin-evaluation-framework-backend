package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.functional.support.CallerIdentityProbeTools;
import com.epam.aidial.evaluation.functional.support.McpFunctionalTestSupport;
import com.epam.aidial.evaluation.mcp.constants.McpToolNames;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP server with {@code spring.ai.mcp.server.protocol=STATELESS} (security mode: none). The
 * stateless transport answers every POST with a plain {@code application/json} JSON-RPC object
 * (no SSE, no {@code Mcp-Session-Id}) that it pre-serializes to a {@code String} and hands to
 * Spring MVC's message converters. This class proves the switch is a pure configuration change
 * (umbrella D11): the response body is a JSON object rather than a double-encoded JSON string
 * (the {@code JsonMapperConfiguration.canWrite(String)} gotcha), and the request-thread caller
 * model, {@code tools/list} and a Core-backed tool all behave as under {@code STREAMABLE}.
 */
@DisplayName("MCP server with protocol=STATELESS (security mode: none)")
public abstract class McpServerStatelessFunctionalTests extends BaseFunctionalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String mcpEndpoint;

    @Value("${spring.ai.mcp.server.protocol}")
    private String configuredProtocol;

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
    @DisplayName("the context really runs the STATELESS protocol")
    void contextRunsStatelessProtocol() {
        assertThat(configuredProtocol).isEqualToIgnoringCase("STATELESS");
    }

    @Test
    @DisplayName("raw initialize POST returns an application/json JSON-RPC object (not a double-encoded string) "
            + "and no Mcp-Session-Id header")
    void rawInitializeReturnsJsonRpcObjectWithoutSession() {
        ResponseEntity<String> response =
                support().rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat(response.getHeaders().getFirst("Mcp-Session-Id")).isNull();
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.isObject())
                .as("JSON-RPC response must be a JSON object, got: %s", response.getBody())
                .isTrue();
        assertThat(body.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(body.get("result").get("serverInfo").get("name").asString()).isNotBlank();
    }

    @Test
    @DisplayName("SDK client initialize and tools/list succeed and advertise exactly the deployment tools")
    void sdkClientInitializeAndToolsListSucceed() {
        client = support().client(Map.of());
        client.initialize();

        var toolNames = client.listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .filter(name -> !name.startsWith("probe_"))
                .toList();

        assertThat(toolNames).containsExactlyInAnyOrder(McpToolNames.LIST_DEPLOYMENTS, McpToolNames.GET_DEPLOYMENT);
    }

    @Test
    @DisplayName("probe_caller_identity runs on the request thread and reports the anonymous caller in none mode")
    void probeReportsAnonymousCallerOnRequestThread() {
        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();

        McpSchema.CallToolResult result = support.callTool(client, CallerIdentityProbeTools.PROBE_TOOL_NAME, Map.of());
        var probe = support.readJson(result);

        assertThat(result.isError()).isFalse();
        assertThat(probe.get("createdBy").asString()).isEqualTo("anonymous");
        assertThat(probe.get("authenticationPresent").asBoolean()).isFalse();
        assertThat(probe.get("credentialKind")).isNull();
    }

    @Test
    @DisplayName("get_deployment reaches the mocked DIAL Core and returns model detail")
    void getDeploymentReturnsModelDetail() {
        when(dialCoreClient.getDeploymentById(eq("gpt-5-mini")))
                .thenReturn(DialCoreModelDto.builder()
                        .id("gpt-5-mini")
                        .displayName("GPT-5 mini")
                        .build());

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of());
        client.initialize();
        McpSchema.CallToolResult result =
                support.callTool(client, McpToolNames.GET_DEPLOYMENT, Map.of("deploymentId", "gpt-5-mini"));
        var payload = support.readJson(result);

        assertThat(result.isError()).isFalse();
        assertThat(payload.get("deploymentId").asString()).isEqualTo("gpt-5-mini");
        assertThat(payload.get("type").asString()).isEqualTo("dial-model");
    }
}
