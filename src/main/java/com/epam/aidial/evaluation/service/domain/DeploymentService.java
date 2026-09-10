package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentType;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Service for listing and fetching deployments (models, applications, and toolsets) from DIAL Core.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class DeploymentService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final DialCoreClient dialCoreClient;
    private final DeploymentMapper deploymentMapper;
    private final SchemaRouteExtractor schemaRouteExtractor;
    private final McpToolInvoker mcpToolInvoker;
    private final ObjectMapper objectMapper;

    /**
     * Fetches all deployments from DIAL Core (models, applications, and toolsets)
     * without any type or interface filtering.
     */
    public List<DeploymentInfoDto> getAllDeployments() {
        return getAllDeployments(null, null);
    }

    /**
     * Fetches all deployments from DIAL Core via the unified /v1/deployments endpoint.
     * Optionally filters by type (client-side) and interface (server-side).
     */
    public List<DeploymentInfoDto> getAllDeployments(DeploymentType type, InterfaceType interfaceType) {
        String interfaceParam = interfaceType != null ? interfaceType.getValue() : null;
        List<DialCoreDeploymentDto> data = dialCoreClient.getDeployments(interfaceParam);

        return data.stream()
                .map(deployment -> {
                    DeploymentInfoDto dto = deploymentMapper.toDeploymentInfoShortDto(deployment);
                    if (dto == null) {
                        log.warn(
                                "Skipping deployment with unknown object type '{}': id='{}'",
                                deployment.getObject(),
                                deployment.getId());
                    }
                    return dto;
                })
                .filter(dto -> dto != null && (type == null || matchesType(dto, type)))
                .toList();
    }

    /**
     * Fetches a single deployment by ID alone, without the caller knowing its type, via DIAL
     * Core's unified {@code GET /v1/deployments/{id}} endpoint, which resolves the type itself.
     *
     * <p>An upstream 404 is translated to {@link EntityNotFoundException} so this endpoint alone
     * returns a real {@code 404} instead of the generic {@code 502} the shared
     * {@code DialCoreErrorMapper} produces for every other {@code DialCoreClient} consumer; any
     * other status is rethrown unchanged and flows through the normal shared handler.
     */
    public DeploymentInfoDto getDeployment(String deploymentId) {
        DialCoreDeploymentDto deployment = getDeploymentByIdOrThrow(deploymentId);
        DeploymentInfoDto dto = toDeploymentInfoDto(deployment);
        dto.setInterfaces(deployment.getInterfaces());
        return dto;
    }

    /**
     * Fetches a single deployment by type and ID from DIAL Core.
     */
    public DeploymentInfoDto getDeployment(DeploymentType deploymentType, String deploymentId) {
        return switch (deploymentType) {
            case DIAL_MODEL -> deploymentMapper.toDialModelInfoDto(dialCoreClient.getModel(deploymentId));
            case DIAL_APPLICATION -> toDialApplicationInfoDto(dialCoreClient.getApplication(deploymentId));
            case DIAL_TOOLSET -> {
                DialCoreToolsetDto toolset = dialCoreClient.getToolset(deploymentId);
                yield deploymentMapper.toToolsetInfoDto(toolset);
            }
        };
    }

    private DialCoreDeploymentDto getDeploymentByIdOrThrow(String deploymentId) {
        try {
            return dialCoreClient.getDeploymentById(deploymentId);
        } catch (DialCoreClientException e) {
            if (e.getStatusCode() != null && e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new EntityNotFoundException("Deployment not found: " + deploymentId);
            }
            throw e;
        }
    }

    /**
     * Maps a raw DIAL Core deployment payload to its API representation, identically to the
     * by-type path — including route resolution for applications.
     */
    private DeploymentInfoDto toDeploymentInfoDto(DialCoreDeploymentDto deployment) {
        return switch (deployment) {
            case DialCoreModelDto model -> deploymentMapper.toDialModelInfoDto(model);
            case DialCoreApplicationDto application -> toDialApplicationInfoDto(application);
            case DialCoreToolsetDto toolset -> deploymentMapper.toToolsetInfoDto(toolset);
            default ->
                throw new DialCoreClientException(
                        HttpStatus.BAD_GATEWAY,
                        "Unsupported DIAL Core deployment payload: "
                                + deployment.getClass().getSimpleName());
        };
    }

    private DialApplicationInfoDto toDialApplicationInfoDto(DialCoreApplicationDto dialCoreApp) {
        DialApplicationInfoDto dto = deploymentMapper.toDialApplicationInfoDto(dialCoreApp);
        Map<String, ApplicationRouteDto> resolvedRoutes = schemaRouteExtractor.resolveRoutes(dialCoreApp);
        if (resolvedRoutes != null) {
            dto.setRoutes(resolvedRoutes);
        }
        return dto;
    }

    /**
     * Lists MCP tools exposed by a deployment via DIAL Core's MCP proxy.
     * If transport is null, STREAMABLE_HTTP is used as default.
     */
    public List<ToolDefinitionDto> listTools(String deploymentId, McpTransport transport) {
        String token = AuthorizationTokenHolder.getToken();
        McpTransport effectiveTransport = transport != null ? transport : McpTransport.STREAMABLE_HTTP;
        List<McpSchema.Tool> tools = mcpToolInvoker.listTools(deploymentId, token, effectiveTransport);
        return tools.stream().map(this::toToolDefinitionDto).toList();
    }

    private ToolDefinitionDto toToolDefinitionDto(McpSchema.Tool tool) {
        Map<String, Object> inputSchemaMap =
                tool.inputSchema() != null ? objectMapper.convertValue(tool.inputSchema(), MAP_TYPE_REF) : null;
        Map<String, Object> outputSchemaMap =
                tool.outputSchema() != null ? objectMapper.convertValue(tool.outputSchema(), MAP_TYPE_REF) : null;
        return ToolDefinitionDto.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(inputSchemaMap)
                .outputSchema(outputSchemaMap)
                .build();
    }

    private static boolean matchesType(DeploymentInfoDto dto, DeploymentType type) {
        return switch (type) {
            case DIAL_MODEL -> dto instanceof DialModelInfoDto;
            case DIAL_APPLICATION -> dto instanceof DialApplicationInfoDto;
            case DIAL_TOOLSET -> dto instanceof ToolsetInfoDto;
        };
    }
}
