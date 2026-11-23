package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import com.epam.aidial.evaluation.service.domain.SuiteTargetResolver;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvaluateeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Derives the evaluatee of a suite from its target; a reference field that is missing leaves the field absent. */
@Component
@LogExecution
@RequiredArgsConstructor
public class SuiteEvaluateeMapper {

    private final SuiteTargetResolver suiteTargetResolver;

    public EvaluateeDto toEvaluatee(TestSuite suite) {
        final SuiteTarget target = suiteTargetResolver.fromEntity(suite);
        return switch (target) {
            case DeploymentTarget deployment -> fromDeployment(deployment.ref());
            case RouteTarget route -> fromRoute(route.ref());
            case McpToolTarget mcp -> fromMcpTool(mcp.ref(), mcp.tool());
        };
    }

    private static EvaluateeDto fromDeployment(DeploymentReferenceDto ref) {
        if (ref == null) {
            return new EvaluateeDto();
        }
        return EvaluateeDto.builder()
                .id(ref.getId())
                .name(ref.getName())
                .type(ref.getType())
                .build();
    }

    private static EvaluateeDto fromRoute(RouteReferenceDto ref) {
        return EvaluateeDto.builder().name(ref != null ? ref.getName() : null).build();
    }

    private static EvaluateeDto fromMcpTool(McpDeploymentReferenceDto ref, ToolReferenceDto tool) {
        return EvaluateeDto.builder()
                .id(ref != null ? ref.getId() : null)
                .name(ref != null ? ref.getName() : null)
                .type(ref != null ? ref.getType() : null)
                .toolName(tool != null ? tool.getName() : null)
                .build();
    }
}
