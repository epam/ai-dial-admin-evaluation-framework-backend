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
import com.epam.aidial.evaluation.runner.util.TokenPropagationHelper;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentType;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
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
    private final DeploymentProbeCollapser deploymentProbeCollapser;

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
     * Fetches a single deployment by ID alone, without the caller knowing its type.
     *
     * <p>Probes all three DIAL Core deployment endpoints concurrently — deployment IDs are globally
     * unique across models, applications and toolsets, so the type is derivable — and collapses the
     * outcomes via {@link DeploymentProbeCollapser}: the winning payload is mapped exactly as the
     * by-type path maps it, and a lookup that resolves nowhere raises one unified upstream failure.
     */
    public DeploymentInfoDto getDeployment(String deploymentId) {
        // Captured on the request thread: AuthorizationTokenHolder is a ThreadLocal and does not
        // cross into the probe threads by itself.
        final String token = AuthorizationTokenHolder.getToken();
        final List<DeploymentProbe> probes;
        try (ExecutorService executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())) {
            final List<CompletableFuture<DeploymentProbe>> futures = List.of(
                    probeAsync(DeploymentType.DIAL_MODEL, () -> dialCoreClient.getModel(deploymentId), token, executor),
                    probeAsync(
                            DeploymentType.DIAL_APPLICATION,
                            () -> dialCoreClient.getApplication(deploymentId),
                            token,
                            executor),
                    probeAsync(
                            DeploymentType.DIAL_TOOLSET,
                            () -> dialCoreClient.getToolset(deploymentId),
                            token,
                            executor));
            probes = futures.stream().map(DeploymentService::awaitProbe).toList();
        }
        return toDeploymentInfoDto(deploymentProbeCollapser.collapse(deploymentId, probes));
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

    private static CompletableFuture<DeploymentProbe> probeAsync(
            DeploymentType type, Supplier<DialCoreDeploymentDto> fetch, String token, Executor executor) {
        return CompletableFuture.supplyAsync(
                TokenPropagationHelper.withToken(token, () -> probe(type, fetch)), executor);
    }

    private static DeploymentProbe probe(DeploymentType type, Supplier<DialCoreDeploymentDto> fetch) {
        try {
            return DeploymentProbe.completed(type, fetch.get());
        } catch (DialCoreClientException e) {
            log.debug("Deployment lookup probe '{}' did not resolve: {}", type.getValue(), e.getMessage(), e);
            return DeploymentProbe.failed(type, e);
        }
    }

    private static DeploymentProbe awaitProbe(CompletableFuture<DeploymentProbe> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DialCoreClientException(HttpStatus.BAD_GATEWAY, "Interrupted while looking up deployment", e);
        } catch (ExecutionException e) {
            throw asDialCoreClientException(e.getCause());
        }
    }

    private static DialCoreClientException asDialCoreClientException(Throwable cause) {
        if (cause instanceof DialCoreClientException dialCoreClientException) {
            return dialCoreClientException;
        }
        final String detail = cause != null ? cause.getMessage() : "unknown cause";
        log.warn("Deployment lookup probe failed unexpectedly: {}", detail, cause);
        return new DialCoreClientException(HttpStatus.BAD_GATEWAY, "Deployment lookup failed: " + detail, cause);
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
