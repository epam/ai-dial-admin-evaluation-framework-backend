package com.epam.aidial.evaluation.runner.model.target;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SuiteTargets")
class SuiteTargetsTest {

    private static final DeploymentReferenceDto DEPLOYMENT =
            DeploymentReferenceDto.builder().id("dep-1").build();
    private static final RouteReferenceDto ROUTE =
            RouteReferenceDto.builder().name("routes/a/b").build();
    private static final McpDeploymentReferenceDto MCP =
            McpDeploymentReferenceDto.builder().id("mcp-1").build();
    private static final ToolReferenceDto TOOL =
            ToolReferenceDto.builder().name("tool-1").build();

    @Test
    @DisplayName("fromSnapshot with null suiteType yields DeploymentTarget")
    void snapshotNullType() {
        final SuiteSnapshotDto snapshot =
                SuiteSnapshotDto.builder().deploymentRef(DEPLOYMENT).build();

        assertThat(SuiteTargets.fromSnapshot(snapshot)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromSnapshot maps DEPLOYMENT")
    void snapshotDeployment() {
        final SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .suiteType("DEPLOYMENT")
                .deploymentRef(DEPLOYMENT)
                .build();

        assertThat(SuiteTargets.fromSnapshot(snapshot)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromSnapshot maps ROUTE")
    void snapshotRoute() {
        final SuiteSnapshotDto snapshot =
                SuiteSnapshotDto.builder().suiteType("ROUTE").routesRef(ROUTE).build();

        assertThat(SuiteTargets.fromSnapshot(snapshot)).isEqualTo(new RouteTarget(ROUTE));
    }

    @Test
    @DisplayName("fromSnapshot maps MCP_TOOL")
    void snapshotMcp() {
        final SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .suiteType("MCP_TOOL")
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .build();

        assertThat(SuiteTargets.fromSnapshot(snapshot)).isEqualTo(new McpToolTarget(MCP, TOOL));
    }

    @Test
    @DisplayName("fromSuiteResponse with null suiteType yields DeploymentTarget")
    void responseNullType() {
        final TestSuiteResponseDto suite =
                TestSuiteResponseDto.builder().deploymentRef(DEPLOYMENT).build();

        assertThat(SuiteTargets.fromSuiteResponse(suite)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromSuiteResponse maps DEPLOYMENT")
    void responseDeployment() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DEPLOYMENT)
                .build();

        assertThat(SuiteTargets.fromSuiteResponse(suite)).isEqualTo(new DeploymentTarget(DEPLOYMENT));
    }

    @Test
    @DisplayName("fromSuiteResponse maps ROUTE")
    void responseRoute() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .suiteType(SuiteType.ROUTE)
                .routesRef(ROUTE)
                .build();

        assertThat(SuiteTargets.fromSuiteResponse(suite)).isEqualTo(new RouteTarget(ROUTE));
    }

    @Test
    @DisplayName("fromSuiteResponse maps MCP_TOOL")
    void responseMcp() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(MCP)
                .toolRef(TOOL)
                .build();

        assertThat(SuiteTargets.fromSuiteResponse(suite)).isEqualTo(new McpToolTarget(MCP, TOOL));
    }
}
