package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.McpSuite;
import com.epam.aidial.evaluation.runner.model.suite.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SuiteDefinitionResolver")
class SuiteDefinitionResolverTest {

    private static final DeploymentReferenceDto DEPLOYMENT =
            DeploymentReferenceDto.builder().id("dep-1").name("Dep").build();
    private static final McpDeploymentReferenceDto MCP =
            McpDeploymentReferenceDto.builder().id("mcp-1").build();
    private static final ToolReferenceDto TOOL =
            ToolReferenceDto.builder().name("tool-1").build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
    private final SuiteDefinitionResolver resolver =
            new SuiteDefinitionResolver(new SuiteTargetResolver(jsonbMapper), jsonbMapper);

    private final EndpointContractDto endpoint0 =
            EndpointContractDto.builder().operationId("op0").build();
    private final RequestTemplateDto template0 =
            RequestTemplateDto.builder().urlTemplate("/zero").build();
    private final List<InputBindingDto> bindings0 =
            List.of(InputBindingDto.builder().templateVariable("v0").build());
    private final List<ResponseColumnDefinitionDto> columns0 =
            List.of(ResponseColumnDefinitionDto.builder().name("c0").build());
    private final RequestDefinitionDto second = RequestDefinitionDto.builder()
            .name("second")
            .endpointRef(EndpointContractDto.builder().operationId("op1").build())
            .requestTemplate(RequestTemplateDto.builder().urlTemplate("/one").build())
            .build();

    @Test
    @DisplayName(
            "fromEntity HTTP suite builds a chain from the entity columns and additionalRequests, nulls normalized")
    void entityHttpChain() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(jsonbMapper.map(DEPLOYMENT))
                .requestName("first")
                .endpointRef(jsonbMapper.map(endpoint0))
                .requestTemplate(jsonbMapper.map(template0))
                .inputBindings(jsonbMapper.mapInputBindings(bindings0))
                .responseColumns(jsonbMapper.mapResponseColumns(columns0))
                .additionalRequests(jsonbMapper.mapAdditionalRequests(List.of(second)))
                .build();

        final HttpSuite http = (HttpSuite) resolver.fromEntity(suite);

        assertThat(http.target()).isEqualTo(new DeploymentTarget(DEPLOYMENT));
        assertThat(http.chain())
                .containsExactly(
                        new RequestExecutionSpec(0, 2, "first", endpoint0, template0, bindings0, columns0),
                        new RequestExecutionSpec(
                                1,
                                2,
                                "second",
                                second.getEndpointRef(),
                                second.getRequestTemplate(),
                                List.of(),
                                List.of()));
    }

    @Test
    @DisplayName("fromEntity MCP_TOOL suite carries argument template, bindings and response columns")
    void entityMcp() {
        final ArgumentTemplateDto args =
                ArgumentTemplateDto.builder().arguments(Map.of("k", "v")).build();
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(MCP))
                .toolRef(jsonbMapper.mapToolRef(TOOL))
                .argumentTemplate(jsonbMapper.mapArgumentTemplate(args))
                .inputBindings(jsonbMapper.mapInputBindings(bindings0))
                .responseColumns(jsonbMapper.mapResponseColumns(columns0))
                .build();

        final McpSuite mcp = (McpSuite) resolver.fromEntity(suite);

        assertThat(mcp.target()).isEqualTo(new McpToolTarget(MCP, TOOL));
        assertThat(mcp.argumentTemplate()).isEqualTo(args);
        assertThat(mcp.inputBindings()).isEqualTo(bindings0);
        assertThat(mcp.responseColumns()).isEqualTo(columns0);
    }

    @Test
    @DisplayName("fromRequest propagates the target validation 400")
    void requestPropagatesValidation() {
        final TestSuiteRequestDto dto =
                TestSuiteRequestDto.builder().suiteType(SuiteType.DEPLOYMENT).build();

        assertThatThrownBy(() -> resolver.fromRequest(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage("deploymentRef is required for DEPLOYMENT suites");
    }

    @Test
    @DisplayName(
            "fromRequest builds the chain from DTO fields (request #0 as-is, additional requests normalized) via httpChain")
    void requestHttpChain() {
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .deploymentRef(DEPLOYMENT)
                .requestName("first")
                .endpointRef(endpoint0)
                .requestTemplate(template0)
                .inputBindings(bindings0)
                .responseColumns(columns0)
                .additionalRequests(List.of(second))
                .build();

        final HttpSuite http = (HttpSuite) resolver.fromRequest(dto);

        assertThat(http.target()).isEqualTo(new DeploymentTarget(DEPLOYMENT));
        assertThat(http.chain())
                .containsExactly(
                        new RequestExecutionSpec(0, 2, "first", endpoint0, template0, bindings0, columns0),
                        new RequestExecutionSpec(
                                1,
                                2,
                                "second",
                                second.getEndpointRef(),
                                second.getRequestTemplate(),
                                List.of(),
                                List.of()));
    }

    @Test
    @DisplayName("fromRequest with null additionalRequests yields a single-spec chain")
    void requestNullAdditional() {
        final TestSuiteRequestDto dto =
                TestSuiteRequestDto.builder().deploymentRef(DEPLOYMENT).build();

        final HttpSuite http = (HttpSuite) resolver.fromRequest(dto);

        assertThat(http.chain()).containsExactly(new RequestExecutionSpec(0, 1, null, null, null, null, null));
    }

    @Test
    @DisplayName("fromRequest MCP_TOOL suite passes argument template, bindings and columns through")
    void requestMcp() {
        final ArgumentTemplateDto args =
                ArgumentTemplateDto.builder().arguments(Map.of("k", "v")).build();
        final TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .argumentTemplate(args)
                .inputBindings(bindings0)
                .responseColumns(columns0)
                .build();

        final McpSuite mcp = (McpSuite) resolver.fromRequest(dto);

        assertThat(mcp.target()).isEqualTo(new McpToolTarget(MCP, TOOL));
        assertThat(mcp.argumentTemplate()).isSameAs(args);
        assertThat(mcp.inputBindings()).isSameAs(bindings0);
        assertThat(mcp.responseColumns()).isSameAs(columns0);
    }
}
