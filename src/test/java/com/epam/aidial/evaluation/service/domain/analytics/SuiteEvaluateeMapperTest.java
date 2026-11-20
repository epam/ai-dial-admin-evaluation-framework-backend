package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.SuiteTargetResolver;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvaluateeDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SuiteEvaluateeMapper")
class SuiteEvaluateeMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
    private final SuiteEvaluateeMapper mapper = new SuiteEvaluateeMapper(new SuiteTargetResolver(jsonbMapper));

    @Test
    @DisplayName("DEPLOYMENT suite: id, name and type come from the deployment reference, no toolName")
    void deployment() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(jsonbMapper.map(DeploymentReferenceDto.builder()
                        .id("gpt-4o")
                        .name("GPT-4o")
                        .type("dial-model")
                        .build()))
                .build();

        assertThat(mapper.toEvaluatee(suite))
                .isEqualTo(EvaluateeDto.builder()
                        .id("gpt-4o")
                        .name("GPT-4o")
                        .type("dial-model")
                        .build());
    }

    @Test
    @DisplayName("MCP_TOOL suite: id, name and type from the MCP deployment reference, toolName from the tool")
    void mcpTool() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("docs-toolset")
                        .name("Docs toolset")
                        .type("dial-toolset")
                        .build()))
                .toolRef(jsonbMapper.mapToolRef(
                        ToolReferenceDto.builder().name("search").build()))
                .build();

        assertThat(mapper.toEvaluatee(suite))
                .isEqualTo(EvaluateeDto.builder()
                        .id("docs-toolset")
                        .name("Docs toolset")
                        .type("dial-toolset")
                        .toolName("search")
                        .build());
    }

    @Test
    @DisplayName("ROUTE suite: only name is set, from the route reference")
    void route() {
        final TestSuite suite = TestSuite.builder()
                .suiteType(SuiteType.ROUTE)
                .routesRef(jsonbMapper.mapRouteRef(RouteReferenceDto.builder()
                        .name("routes/platform/validate-route")
                        .build()))
                .build();

        assertThat(mapper.toEvaluatee(suite))
                .isEqualTo(EvaluateeDto.builder()
                        .name("routes/platform/validate-route")
                        .build());
    }

    @Test
    @DisplayName("fields the reference does not carry stay absent: deployment without type, MCP without tool or name")
    void missingReferenceFields() {
        final TestSuite deployment = TestSuite.builder()
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(jsonbMapper.map(
                        DeploymentReferenceDto.builder().id("dep-1").name("Dep").build()))
                .build();
        final TestSuite mcp = TestSuite.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("m")
                        .type("dial-toolset")
                        .build()))
                .build();
        final TestSuite noReference =
                TestSuite.builder().suiteType(SuiteType.ROUTE).build();

        assertThat(mapper.toEvaluatee(deployment).getType()).isNull();
        assertThat(mapper.toEvaluatee(mcp))
                .isEqualTo(EvaluateeDto.builder().id("m").type("dial-toolset").build());
        assertThat(mapper.toEvaluatee(noReference)).isEqualTo(new EvaluateeDto());
    }
}
