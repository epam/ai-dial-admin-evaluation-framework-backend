package com.epam.aidial.evaluation.mcp.model;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Detail shape returned by {@code get_deployment} (D-F8). Exactly one of {@link #model()},
 * {@link #toolset()} and {@link #application()} is populated, matching {@link #type()}. Routes,
 * pricing, icon URLs and input attachment types are never included. {@code features} is a plain
 * {@code Map}, so explicit JSON nulls inside it are not preserved (dropped by the shared
 * {@code NON_NULL} mapper configuration); acceptable for read-only metadata. {@code createdAt}
 * and {@code updatedAt} stay epoch milliseconds, per the project's API timestamp convention.
 */
public record DeploymentMcpDto(
        String deploymentId,
        DeploymentKind type,
        @Nullable String displayName,
        @Nullable String version,
        @Nullable String description,
        @Nullable String owner,
        @Nullable List<DeploymentInterface> interfaces,
        @Nullable Map<String, Object> features,
        @Nullable Long createdAt,
        @Nullable Long updatedAt,
        @Nullable ModelDetailsMcpDto model,
        @Nullable ToolsetDetailsMcpDto toolset,
        @Nullable ApplicationDetailsMcpDto application) {}
