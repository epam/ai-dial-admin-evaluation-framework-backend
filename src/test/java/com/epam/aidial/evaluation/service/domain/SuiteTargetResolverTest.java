package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SuiteTargetResolver")
class SuiteTargetResolverTest {

    private static final DeploymentReferenceDto DEPLOYMENT =
            DeploymentReferenceDto.builder().id("dep-1").name("Dep").build();
    private static final RouteReferenceDto ROUTE =
            RouteReferenceDto.builder().name("routes/a/b").build();
    private static final McpDeploymentReferenceDto MCP =
            McpDeploymentReferenceDto.builder().id("mcp-1").build();
    private static final ToolReferenceDto TOOL =
            ToolReferenceDto.builder().name("tool-1").build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
    private final SuiteTargetResolver resolver = new SuiteTargetResolver(jsonbMapper);

    @Test
    @DisplayName("fromEntity maps DEPLOYMENT")
    void entityDeployment() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(jsonbMapper.map(DEPLOYMENT))
                .build();

        assertThat(resolver.fromEntity(suite)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromEntity with null suiteType yields DeploymentTarget")
    void entityNullType() {
        final TestSuite suite =
                TestSuite.builder().deploymentRef(jsonbMapper.map(DEPLOYMENT)).build();

        assertThat(resolver.fromEntity(suite)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromEntity maps ROUTE")
    void entityRoute() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.ROUTE)
                .routesRef(jsonbMapper.mapRouteRef(ROUTE))
                .build();

        assertThat(resolver.fromEntity(suite)).isEqualTo(new RouteTarget(ROUTE));
    }

    @Test
    @DisplayName("fromEntity maps MCP_TOOL")
    void entityMcp() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(MCP))
                .toolRef(jsonbMapper.mapToolRef(TOOL))
                .build();

        assertThat(resolver.fromEntity(suite)).isEqualTo(new McpToolTarget(MCP, TOOL));
    }

    @Test
    @DisplayName("fromRequest maps DEPLOYMENT")
    void requestDeployment() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DEPLOYMENT)
                .build();

        assertThat(resolver.fromRequest(dto)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromRequest with null suiteType yields DeploymentTarget")
    void requestNullType() {
        final TestSuiteRequestDto dto =
                TestSuiteRequestDto.builder().deploymentRef(DEPLOYMENT).build();

        assertThat(resolver.fromRequest(dto)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromRequest maps ROUTE")
    void requestRoute() {
        final TestSuiteRequestDto dto = routeRequest();

        assertThat(resolver.fromRequest(dto)).isEqualTo(new RouteTarget(ROUTE));
    }

    @Test
    @DisplayName("fromRequest maps MCP_TOOL")
    void requestMcp() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .build();

        assertThat(resolver.fromRequest(dto)).isEqualTo(new McpToolTarget(MCP, TOOL));
    }

    @Test
    @DisplayName("fromRequest requires deploymentRef for DEPLOYMENT")
    void requestDeploymentRequiresRef() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder().build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("deploymentRef is required for DEPLOYMENT suites");
    }

    @Test
    @DisplayName("fromRequest rejects routesRef on DEPLOYMENT")
    void requestDeploymentRejectsRoutesRef() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .deploymentRef(DEPLOYMENT)
                .routesRef(ROUTE)
                .build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("routesRef must be empty for DEPLOYMENT suites");
    }

    @Test
    @DisplayName("fromRequest requires mcpDeploymentRef for MCP_TOOL")
    void requestMcpRequiresDeploymentRef() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .toolRef(TOOL)
                .build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("mcpDeploymentRef is required for MCP_TOOL suites");
    }

    @Test
    @DisplayName("fromRequest requires toolRef for MCP_TOOL")
    void requestMcpRequiresToolRef() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(MCP)
                .build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("toolRef is required for MCP_TOOL suites");
    }

    @Test
    @DisplayName("fromRequest rejects non-empty additionalRequests on MCP_TOOL")
    void requestMcpRejectsAdditionalRequests() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .additionalRequests(List.of(RequestDefinitionDto.builder().build()))
                .build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("additionalRequests must be empty for MCP_TOOL suites");
    }

    @Test
    @DisplayName("fromRequest rejects routesRef on MCP_TOOL")
    void requestMcpRejectsRoutesRef() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .routesRef(ROUTE)
                .build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("routesRef must be empty for MCP_TOOL suites");
    }

    @Test
    @DisplayName("fromRequest rejects mcpDeploymentRef on ROUTE")
    void requestRouteRejectsMcpDeploymentRef() {
        final TestSuiteRequestDto dto = routeRequest();
        dto.setMcpDeploymentRef(MCP);

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("mcpDeploymentRef must be empty for ROUTE suites");
    }

    @Test
    @DisplayName("fromRequest rejects argumentTemplate on ROUTE")
    void requestRouteRejectsArgumentTemplate() {
        final TestSuiteRequestDto dto = routeRequest();
        dto.setArgumentTemplate(ArgumentTemplateDto.builder().build());

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("argumentTemplate must be empty for ROUTE suites");
    }

    private static TestSuiteRequestDto routeRequest() {
        return TestSuiteRequestDto.builder()
                .suiteType(SuiteType.ROUTE)
                .routesRef(ROUTE)
                .endpointRef(EndpointContractDto.builder().build())
                .requestTemplate(RequestTemplateDto.builder().build())
                .build();
    }
}
