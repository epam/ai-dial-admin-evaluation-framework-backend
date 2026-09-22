package com.epam.aidial.evaluation.mcp.model;

import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * List shape returned by {@code list_deployments} (D-F8). Deliberately lean: no detail fields
 * (limits, capabilities, allowed tools, routes, pricing, owner, version, timestamps) — agents
 * fetch those via {@code get_deployment}. {@code toolsetTransport} is populated only for
 * {@link DeploymentKind#DIAL_TOOLSET} entries; null-valued fields are omitted from the JSON by
 * the shared {@code NON_NULL} mapper configuration.
 */
public record DeploymentSummaryMcpDto(
        String deploymentId,
        DeploymentKind type,
        @Nullable String displayName,
        @Nullable String description,
        @Nullable List<DeploymentInterface> interfaces,
        @Nullable McpTransport toolsetTransport) {}
