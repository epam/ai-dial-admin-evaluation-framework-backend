package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Derives a {@link SuiteTarget} from the flat suite entity or request DTO. */
@Component
@LogExecution
@RequiredArgsConstructor
public class SuiteTargetResolver {

    private final JsonbMapper jsonbMapper;

    /** Derives the target from the persisted entity, whose reference fields are JSONB strings. */
    public SuiteTarget fromEntity(TestSuite suite) {
        return switch (suite.getSuiteType()) {
            case DEPLOYMENT -> new DeploymentTarget(jsonbMapper.map(suite.getDeploymentRef()));
            case ROUTE -> new RouteTarget(jsonbMapper.mapRouteRef(suite.getRoutesRef()));
            case MCP_TOOL ->
                new McpToolTarget(
                        jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef()),
                        jsonbMapper.mapToolRef(suite.getToolRef()));
        };
    }

    /**
     * Derives the target from the request DTO, enforcing that exactly one target is present with its
     * hard-required fields (HTTP 400 otherwise).
     * DEPLOYMENT: only deploymentRef is hard-required. MCP_TOOL: mcpDeploymentRef and toolRef.
     * ROUTE: routesRef, endpointRef and requestTemplate; the other targets' fields must be empty.
     */
    public SuiteTarget fromRequest(TestSuiteRequestDto dto) {
        final SuiteType suiteType = dto.getSuiteType();
        return switch (suiteType) {
            case DEPLOYMENT -> {
                if (dto.getDeploymentRef() == null) {
                    throw new ValidationException("deploymentRef is required for DEPLOYMENT suites");
                }
                rejectRoutesRef(dto, suiteType);
                yield new DeploymentTarget(dto.getDeploymentRef());
            }
            case MCP_TOOL -> {
                if (dto.getMcpDeploymentRef() == null) {
                    throw new ValidationException("mcpDeploymentRef is required for MCP_TOOL suites");
                }
                if (dto.getToolRef() == null) {
                    throw new ValidationException("toolRef is required for MCP_TOOL suites");
                }
                if (dto.getAdditionalRequests() != null
                        && !dto.getAdditionalRequests().isEmpty()) {
                    throw new ValidationException("additionalRequests must be empty for MCP_TOOL suites");
                }
                rejectRoutesRef(dto, suiteType);
                yield new McpToolTarget(dto.getMcpDeploymentRef(), dto.getToolRef());
            }
            case ROUTE -> routeTarget(dto);
        };
    }

    private RouteTarget routeTarget(TestSuiteRequestDto dto) {
        if (dto.getRoutesRef() == null) {
            throw new ValidationException("routesRef is required for ROUTE suites");
        }
        if (dto.getEndpointRef() == null) {
            throw new ValidationException("endpointRef is required for ROUTE suites");
        }
        if (dto.getRequestTemplate() == null) {
            throw new ValidationException("requestTemplate is required for ROUTE suites");
        }
        rejectRouteField(dto.getDeploymentRef(), "deploymentRef");
        rejectRouteField(dto.getMcpDeploymentRef(), "mcpDeploymentRef");
        rejectRouteField(dto.getToolRef(), "toolRef");
        rejectRouteField(dto.getArgumentTemplate(), "argumentTemplate");
        return new RouteTarget(dto.getRoutesRef());
    }

    private static void rejectRoutesRef(TestSuiteRequestDto dto, SuiteType suiteType) {
        if (dto.getRoutesRef() != null) {
            throw new ValidationException("routesRef must be empty for " + suiteType + " suites");
        }
    }

    private static void rejectRouteField(Object value, String fieldName) {
        if (value != null) {
            throw new ValidationException(fieldName + " must be empty for ROUTE suites");
        }
    }
}
