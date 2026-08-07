package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.DeploymentService;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentType;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolDefinitionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for deployment listing and retrieval (models and applications from DIAL Core).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/deployments")
@RequiredArgsConstructor
@LogExecution
@Tag(name = "Deployments", description = "List and get deployments (models and applications) from DIAL Core")
public class DeploymentController {

    private final DeploymentService deploymentService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List all deployments",
            description = "Returns all deployments (models and applications) from DIAL Core. Requires authentication; "
                    + "user's JWT is propagated to DIAL Core so only authorized deployments are returned.")
    @ApiResponse(
            responseCode = "200",
            description = "Merged list of models and applications",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DeploymentInfoDto.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized (missing or invalid token to this service)")
    @ApiResponse(
            responseCode = "502",
            description =
                    "Upstream (DIAL Core) error; check errorCode for UPSTREAM_AUTH_ERROR, UPSTREAM_NOT_FOUND, or UPSTREAM_ERROR")
    public List<DeploymentInfoDto> getAllDeployments(
            @Parameter(description = "Filter by deployment type (e.g. dial-model, dial-application, dial-toolset)")
                    @RequestParam(required = false)
                    String type,
            @Parameter(description = "Filter by interface type (e.g. chat, embedding, mcp)")
                    @RequestParam(name = "interface", required = false)
                    String interfaceType) {
        DeploymentType deploymentType = type != null ? DeploymentType.fromValue(type) : null;
        InterfaceType ifType = interfaceType != null ? InterfaceType.fromValue(interfaceType) : null;
        return deploymentService.getAllDeployments(deploymentType, ifType);
    }

    @GetMapping(value = "/{deploymentType}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get deployment by type passed in url and id passed as query param",
        description = "Returns a single deployment. Use deploymentType 'dial-model', 'dial-application', "
                      + "or 'dial-toolset' (kebab-case).")
    @ApiResponse(
        responseCode = "200",
        description = "Deployment found",
        content =
        @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DeploymentInfoDto.class)))
    @ApiResponse(
        responseCode = "400",
        description = "Invalid deployment type " + "(valid values: dial-model, dial-application, dial-toolset)")
    @ApiResponse(responseCode = "401", description = "Unauthorized (missing or invalid token to this service)")
    @ApiResponse(responseCode = "403", description = "Forbidden (no access to this deployment in DIAL Core)")
    @ApiResponse(
        responseCode = "502",
        description =
            "Upstream (DIAL Core) error; check errorCode for UPSTREAM_AUTH_ERROR, UPSTREAM_NOT_FOUND, or UPSTREAM_ERROR")
    public DeploymentInfoDto getDeploymentViaParamId(
        @Parameter(description = "Deployment type: dial-model, dial-application, or dial-toolset", required = true)
        @PathVariable
        String deploymentType,
        @RequestParam(name = "deploymentId", required = false) String deploymentId) {
        final DeploymentType type = DeploymentType.fromValue(deploymentType);
        return deploymentService.getDeployment(type, deploymentId);
    }

    @GetMapping(value = "/{deploymentType}/{deploymentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get deployment by type and ID",
            description = "Returns a single deployment. Use deploymentType 'dial-model', 'dial-application', "
                    + "or 'dial-toolset' (kebab-case).")
    @ApiResponse(
            responseCode = "200",
            description = "Deployment found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DeploymentInfoDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid deployment type " + "(valid values: dial-model, dial-application, dial-toolset)")
    @ApiResponse(responseCode = "401", description = "Unauthorized (missing or invalid token to this service)")
    @ApiResponse(responseCode = "403", description = "Forbidden (no access to this deployment in DIAL Core)")
    @ApiResponse(
            responseCode = "502",
            description =
                    "Upstream (DIAL Core) error; check errorCode for UPSTREAM_AUTH_ERROR, UPSTREAM_NOT_FOUND, or UPSTREAM_ERROR")
    public DeploymentInfoDto getDeployment(
            @Parameter(description = "Deployment type: dial-model, dial-application, or dial-toolset", required = true)
                    @PathVariable
                    String deploymentType,
            @Parameter(description = "Deployment ID", required = true) @PathVariable String deploymentId) {
        final DeploymentType type = DeploymentType.fromValue(deploymentType);
        return deploymentService.getDeployment(type, deploymentId);
    }

    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List tools for a deployment",
            description = "Returns the list of MCP tools exposed by a deployment (toolset or application). "
                    + "The deployment ID may contain slashes (e.g. toolsets/public/my-tool) — "
                    + "pass it as a plain query parameter value, no extra encoding needed. "
                    + "Use the transport parameter to select the MCP transport protocol; "
                    + "defaults to streamable-http when omitted.")
    @ApiResponse(
            responseCode = "200",
            description = "List of tools",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ToolDefinitionDto.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized (missing or invalid token to this service)")
    @ApiResponse(responseCode = "502", description = "Upstream MCP endpoint error")
    public List<ToolDefinitionDto> listTools(
            @Parameter(
                            description = "Deployment ID (may contain slashes, e.g. toolsets/public/my-tool)",
                            required = true)
                    @RequestParam
                    String deploymentId,
            @Parameter(description = "MCP transport protocol: streamable-http (default) or sse")
                    @RequestParam(required = false)
                    McpTransport transport) {
        return deploymentService.listTools(deploymentId, transport);
    }
}
