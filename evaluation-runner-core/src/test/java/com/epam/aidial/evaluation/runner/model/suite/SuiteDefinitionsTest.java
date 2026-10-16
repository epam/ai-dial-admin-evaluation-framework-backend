package com.epam.aidial.evaluation.runner.model.suite;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SuiteDefinitions")
class SuiteDefinitionsTest {

    private static final DeploymentReferenceDto DEPLOYMENT =
            DeploymentReferenceDto.builder().id("dep-1").build();
    private static final McpDeploymentReferenceDto MCP =
            McpDeploymentReferenceDto.builder().id("mcp-1").build();
    private static final ToolReferenceDto TOOL =
            ToolReferenceDto.builder().name("tool-1").build();

    private final EndpointContractDto endpoint0 =
            EndpointContractDto.builder().operationId("op0").build();
    private final RequestTemplateDto template0 =
            RequestTemplateDto.builder().urlTemplate("/zero").build();
    private final List<InputBindingDto> bindings0 =
            List.of(InputBindingDto.builder().templateVariable("v0").build());
    private final List<ResponseColumnDefinitionDto> columns0 =
            List.of(ResponseColumnDefinitionDto.builder().name("c0").build());

    private SuiteSnapshotDto.SuiteSnapshotDtoBuilder httpSnapshot() {
        return SuiteSnapshotDto.builder()
                .deploymentRef(DEPLOYMENT)
                .requestName("first")
                .endpointRef(endpoint0)
                .requestTemplate(template0)
                .inputBindings(bindings0)
                .responseColumns(columns0);
    }

    @Test
    @DisplayName("fromSnapshot single-request HTTP suite yields a one-spec chain from the suite's own fields")
    void snapshotHttpSingle() {
        final SuiteDefinition def = SuiteDefinitions.fromSnapshot(httpSnapshot().build());

        final HttpSuite http = (HttpSuite) def;
        assertThat(http.target()).isEqualTo(new DeploymentTarget(DEPLOYMENT));
        assertThat(http.chain())
                .containsExactly(new RequestExecutionSpec(0, 1, "first", endpoint0, template0, bindings0, columns0));
        assertThat(http.first()).isSameAs(http.chain().get(0));
    }

    @Test
    @DisplayName("fromSnapshot multi-request suite appends additional requests, null lists normalized to empty")
    void snapshotHttpMulti() {
        final EndpointContractDto endpoint1 =
                EndpointContractDto.builder().operationId("op1").build();
        final RequestTemplateDto template1 =
                RequestTemplateDto.builder().urlTemplate("/one").build();
        final RequestDefinitionDto second = RequestDefinitionDto.builder()
                .name("second")
                .endpointRef(endpoint1)
                .requestTemplate(template1)
                .build();

        final HttpSuite http = (HttpSuite) SuiteDefinitions.fromSnapshot(
                httpSnapshot().additionalRequests(List.of(second)).build());

        assertThat(http.chain())
                .containsExactly(
                        new RequestExecutionSpec(0, 2, "first", endpoint0, template0, bindings0, columns0),
                        new RequestExecutionSpec(1, 2, "second", endpoint1, template1, List.of(), List.of()));
    }

    @Test
    @DisplayName("fromSnapshot with null suiteType is a DEPLOYMENT suite; null lists stay null for request #0")
    void snapshotLegacyNullTypeAndNullLists() {
        final SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .deploymentRef(DEPLOYMENT)
                .additionalRequests(null)
                .build();

        final HttpSuite http = (HttpSuite) SuiteDefinitions.fromSnapshot(snapshot);

        assertThat(http.target()).isEqualTo(new DeploymentTarget(DEPLOYMENT));
        assertThat(http.chain()).containsExactly(new RequestExecutionSpec(0, 1, null, null, null, null, null));
    }

    @Test
    @DisplayName("fromSnapshot ROUTE suite yields an HttpSuite with a RouteTarget")
    void snapshotRoute() {
        final RouteReferenceDto route =
                RouteReferenceDto.builder().name("routes/a/b").build();
        final SuiteSnapshotDto snapshot = httpSnapshot()
                .suiteType(SuiteType.ROUTE.name())
                .deploymentRef(null)
                .routesRef(route)
                .build();

        final HttpSuite http = (HttpSuite) SuiteDefinitions.fromSnapshot(snapshot);

        assertThat(http.target()).isEqualTo(new RouteTarget(route));
    }

    @Test
    @DisplayName("fromSnapshot MCP_TOOL suite yields an McpSuite carrying argument template, bindings and columns")
    void snapshotMcp() {
        final ArgumentTemplateDto args = new ArgumentTemplateDto();
        final SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .suiteType(SuiteType.MCP_TOOL.name())
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .argumentTemplate(args)
                .inputBindings(bindings0)
                .responseColumns(columns0)
                .build();

        final McpSuite mcp = (McpSuite) SuiteDefinitions.fromSnapshot(snapshot);

        assertThat(mcp.target()).isEqualTo(new McpToolTarget(MCP, TOOL));
        assertThat(mcp.argumentTemplate()).isSameAs(args);
        assertThat(mcp.inputBindings()).isSameAs(bindings0);
        assertThat(mcp.responseColumns()).isSameAs(columns0);
    }

    @Test
    @DisplayName("fromSuiteResponse defaults null bindings, columns and additionalRequests to empty")
    void suiteResponseDefaults() {
        final TestSuiteResponseDto suite = new TestSuiteResponseDto();
        suite.setDeploymentRef(DEPLOYMENT);
        suite.setRequestName("first");
        suite.setEndpointRef(endpoint0);

        final HttpSuite http = (HttpSuite) SuiteDefinitions.fromSuiteResponse(suite);

        assertThat(http.target()).isEqualTo(new DeploymentTarget(DEPLOYMENT));
        assertThat(http.chain())
                .containsExactly(new RequestExecutionSpec(0, 1, "first", endpoint0, null, List.of(), List.of()));
    }

    @Test
    @DisplayName("fromSuiteResponse MCP_TOOL suite defaults null bindings and columns to empty")
    void suiteResponseMcp() {
        final ArgumentTemplateDto args = new ArgumentTemplateDto();
        final TestSuiteResponseDto suite = new TestSuiteResponseDto();
        suite.setSuiteType(SuiteType.MCP_TOOL);
        suite.setMcpDeploymentRef(MCP);
        suite.setToolRef(TOOL);
        suite.setArgumentTemplate(args);

        final McpSuite mcp = (McpSuite) SuiteDefinitions.fromSuiteResponse(suite);

        assertThat(mcp.target()).isEqualTo(new McpToolTarget(MCP, TOOL));
        assertThat(mcp.argumentTemplate()).isSameAs(args);
        assertThat(mcp.inputBindings()).isEmpty();
        assertThat(mcp.responseColumns()).isEmpty();
    }

    @Test
    @DisplayName("HttpSuite.withTarget swaps the target and keeps the chain")
    void withTarget() {
        final HttpSuite http =
                (HttpSuite) SuiteDefinitions.fromSnapshot(httpSnapshot().build());
        final DeploymentTarget other = new DeploymentTarget(
                DeploymentReferenceDto.builder().id("dep-2").build());

        final HttpSuite swapped = http.withTarget(other);

        assertThat(swapped.target()).isEqualTo(other);
        assertThat(swapped.chain()).isSameAs(http.chain());
    }
}
