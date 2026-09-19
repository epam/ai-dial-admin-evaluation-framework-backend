package com.epam.aidial.evaluation.mcp.tools.deployment;

import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.mcp.constants.McpToolDescriptions;
import com.epam.aidial.evaluation.mcp.constants.McpToolNames;
import com.epam.aidial.evaluation.mcp.mapper.DeploymentMcpMapper;
import com.epam.aidial.evaluation.mcp.model.DeploymentInterface;
import com.epam.aidial.evaluation.mcp.model.DeploymentKind;
import com.epam.aidial.evaluation.mcp.support.McpToolExecutor;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.DeploymentService;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The deployments tool group (D-F8): {@code list_deployments} and {@code get_deployment}, letting
 * an agent discover the DIAL deployments it may evaluate and inspect one deployment's invocation
 * interfaces before creating a test suite. Both filter parameters of {@code list_deployments} are
 * declared as {@code String} rather than an enum: the schema generator would otherwise advertise
 * Java constant names while binding rejects them (Context), so parsing and validation happen
 * inside {@link McpToolExecutor#execute}, and every value error reaches the caller through the
 * structured tool error contract instead of Spring AI's plain-text binding error.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class DeploymentTools {

    private final McpToolExecutor executor;
    private final DeploymentService deploymentService;
    private final DeploymentMcpMapper mapper;

    @McpTool(name = McpToolNames.LIST_DEPLOYMENTS, description = McpToolDescriptions.LIST_DEPLOYMENTS)
    public CallToolResult listDeployments(
            @McpToolParam(description = McpToolDescriptions.DEPLOYMENT_TYPE_FILTER, required = false) @Nullable
                    String type,
            @McpToolParam(description = McpToolDescriptions.INTERFACE_TYPE_FILTER, required = false) @Nullable
                    String interfaceType) {
        return executor.execute(() -> {
            DeploymentType parsedType = type == null ? null : toDeploymentType(type);
            InterfaceType parsedInterfaceType = interfaceType == null ? null : toInterfaceType(interfaceType);
            List<DeploymentInfoDto> deployments = deploymentService.getAllDeployments(parsedType, parsedInterfaceType);
            return mapper.toList(deployments);
        });
    }

    @McpTool(name = McpToolNames.GET_DEPLOYMENT, description = McpToolDescriptions.GET_DEPLOYMENT)
    public CallToolResult getDeployment(
            @McpToolParam(description = McpToolDescriptions.DEPLOYMENT_ID, required = true) String deploymentId) {
        return executor.execute(() -> {
            if (deploymentId == null || deploymentId.isBlank()) {
                throw new ValidationException("deploymentId must not be blank");
            }
            return mapper.toDetail(deploymentService.getDeployment(deploymentId));
        });
    }

    /**
     * Parses and validates via the MCP-owned {@link DeploymentKind} first (raising
     * {@link ValidationException} listing accepted values on a bad filter), then converts to the
     * service-facing {@link DeploymentType} by the same wire value — guaranteed to succeed since
     * both enums share the same accepted wire values.
     */
    private static DeploymentType toDeploymentType(String value) {
        return DeploymentType.fromValue(DeploymentKind.fromWireValue(value).getWireValue());
    }

    /**
     * Same contract as {@link #toDeploymentType(String)}, for {@link DeploymentInterface} /
     * {@link InterfaceType}.
     */
    private static InterfaceType toInterfaceType(String value) {
        return InterfaceType.fromValue(DeploymentInterface.fromWireValue(value).getWireValue());
    }
}
