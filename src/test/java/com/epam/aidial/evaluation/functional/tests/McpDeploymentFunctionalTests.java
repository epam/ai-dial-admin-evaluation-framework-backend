package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialTransport;
import com.epam.aidial.evaluation.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.client.mcp.McpTransport;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Functional tests for MCP deployment listing, toolset detail, and tool discovery.
 * Covers tasks 13.1 (deployment listing with type/interface filters),
 * 13.2 (single toolset detail), and 13.3 (tool discovery).
 */
@DisplayName("MCP Deployment Functional Tests")
public abstract class McpDeploymentFunctionalTests extends BaseFunctionalTest {

    @Autowired
    protected DialCoreClient dialCoreClient;

    @Autowired
    protected McpToolInvoker mcpToolInvoker;

    @BeforeEach
    void resetMocks() {
        reset(dialCoreClient, mcpToolInvoker);
    }

    // --- 13.1 Deployment listing with type/interface filters ---

    @Test
    @DisplayName("GET /deployments returns models, apps, and toolsets from unified endpoint")
    void getAllDeploymentsReturnsAllTypes() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(
                        DialCoreDeploymentDto.builder()
                                .object("model")
                                .id("m1")
                                .displayName("Model 1")
                                .build(),
                        DialCoreDeploymentDto.builder()
                                .object("application")
                                .id("a1")
                                .displayName("App 1")
                                .build(),
                        DialCoreDeploymentDto.builder()
                                .object("toolset")
                                .id("t1")
                                .displayName("Toolset 1")
                                .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3);
        assertThat(response.getBody())
                .extracting(DeploymentInfoDto::getDeploymentId)
                .containsExactlyInAnyOrder("m1", "a1", "t1");
    }

    @Test
    @DisplayName("GET /deployments?interface=mcp passes interface param to DIAL Core")
    void getDeploymentsWithMcpInterfaceFilter() {
        when(dialCoreClient.getDeployments(eq("mcp")))
                .thenReturn(List.of(
                        DialCoreDeploymentDto.builder()
                                .object("toolset")
                                .id("t1")
                                .displayName("MCP Toolset")
                                .build(),
                        DialCoreDeploymentDto.builder()
                                .object("application")
                                .id("a1")
                                .displayName("MCP App")
                                .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments?interface=mcp"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody())
                .extracting(DeploymentInfoDto::getDeploymentId)
                .containsExactlyInAnyOrder("t1", "a1");
    }

    @Test
    @DisplayName("GET /deployments?type=dial-toolset filters client-side to toolsets only")
    void getDeploymentsWithTypeFilterToolset() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(
                        DialCoreDeploymentDto.builder()
                                .object("model")
                                .id("m1")
                                .displayName("Model 1")
                                .build(),
                        DialCoreDeploymentDto.builder()
                                .object("toolset")
                                .id("t1")
                                .displayName("Toolset 1")
                                .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments?type=dial-toolset"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).isInstanceOf(ToolsetInfoDto.class);
        assertThat(response.getBody().get(0).getDeploymentId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("GET /deployments?type=dial-toolset&interface=mcp applies both filters")
    void getDeploymentsWithCombinedFilters() {
        when(dialCoreClient.getDeployments(eq("mcp")))
                .thenReturn(List.of(
                        DialCoreDeploymentDto.builder()
                                .object("toolset")
                                .id("t1")
                                .displayName("Toolset 1")
                                .build(),
                        DialCoreDeploymentDto.builder()
                                .object("application")
                                .id("a1")
                                .displayName("MCP App")
                                .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments?type=dial-toolset&interface=mcp"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getDeploymentId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("GET /deployments?type=dial-model returns only models")
    void getDeploymentsWithTypeFilterModel() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(
                        DialCoreDeploymentDto.builder()
                                .object("model")
                                .id("m1")
                                .displayName("Model 1")
                                .build(),
                        DialCoreDeploymentDto.builder()
                                .object("toolset")
                                .id("t1")
                                .displayName("Toolset 1")
                                .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments?type=dial-model"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).isInstanceOf(DialModelInfoDto.class);
        assertThat(response.getBody().get(0).getDeploymentId()).isEqualTo("m1");
    }

    // --- 13.2 Single toolset detail ---

    @Test
    @DisplayName("GET /deployments/dial-toolset/{id} returns toolset detail")
    void getToolsetDeploymentReturnsDetail() {
        when(dialCoreClient.getToolset(eq("my-toolset")))
                .thenReturn(DialCoreToolsetDto.builder()
                        .id("my-toolset")
                        .displayName("My Toolset")
                        .description("Test toolset")
                        .transport(DialTransport.HTTP)
                        .allowedTools(List.of("search", "calculate"))
                        .build());

        ResponseEntity<ToolsetInfoDto> response =
                restTemplate.getForEntity(apiUrl("/deployments/dial-toolset/my-toolset"), ToolsetInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo("my-toolset");
        assertThat(response.getBody().getDisplayName()).isEqualTo("My Toolset");
        assertThat(response.getBody().getTransport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(response.getBody().getAllowedTools()).containsExactly("search", "calculate");
    }

    // --- 13.3 Tool discovery ---

    @Test
    @DisplayName("GET /deployments/tools?deploymentId=... returns tool list with input schema")
    void listToolsReturnsTools() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", Map.of("query", Map.of("type", "string")), List.of("query"), null, null, null);
        when(mcpToolInvoker.listTools(eq("my-toolset"), any(), any()))
                .thenReturn(
                        List.of(new McpSchema.Tool("search", null, "Search the web", inputSchema, null, null, null)));

        ResponseEntity<List<ToolDefinitionDto>> response = restTemplate.exchange(
                apiUrl("/deployments/tools?deploymentId=my-toolset"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getName()).isEqualTo("search");
        assertThat(response.getBody().get(0).getDescription()).isEqualTo("Search the web");
        assertThat(response.getBody().get(0).getInputSchema()).isNotNull();
    }

    @Test
    @DisplayName("GET /deployments/tools?deploymentId=... with slash-containing ID works without encoding issues")
    void listToolsWithSlashContainingIdWorks() {
        String deploymentId = "toolsets/public/3DMolVisualizer_(copy)__0.0.2";
        when(mcpToolInvoker.listTools(eq(deploymentId), any(), any()))
                .thenReturn(
                        List.of(new McpSchema.Tool("visualize", null, "Visualize molecule", null, null, null, null)));

        URI uri = UriComponentsBuilder.fromHttpUrl(apiUrl("/deployments/tools"))
                .queryParam("deploymentId", deploymentId)
                .build()
                .encode()
                .toUri();

        ResponseEntity<List<ToolDefinitionDto>> response =
                restTemplate.exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getName()).isEqualTo("visualize");
    }

    @Test
    @DisplayName("GET /deployments/tools?deploymentId=... returns 502 when MCP endpoint fails")
    void listToolsReturns502OnMcpError() {
        when(mcpToolInvoker.listTools(eq("failing-toolset"), any(), any()))
                .thenThrow(
                        new McpInvocationException(502, "MCP_ERROR", "MCP tool invocation failed: Connection refused"));

        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/deployments/tools?deploymentId=failing-toolset"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("UPSTREAM_ERROR");
    }

    @Test
    @DisplayName("GET /deployments/tools?deploymentId=... returns 504 on MCP timeout")
    void listToolsReturns504OnMcpTimeout() {
        when(mcpToolInvoker.listTools(eq("slow-toolset"), any(), any()))
                .thenThrow(new McpInvocationException(504, "MCP_TIMEOUT", "MCP tool invocation timed out"));

        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/deployments/tools?deploymentId=slow-toolset"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("UPSTREAM_TIMEOUT");
    }
}
