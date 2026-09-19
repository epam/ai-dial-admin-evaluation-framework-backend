package com.epam.aidial.evaluation.mcp.model;

import org.jspecify.annotations.Nullable;

/**
 * Type-specific detail nested under {@link DeploymentMcpDto#model()} for
 * {@link DeploymentKind#DIAL_MODEL} deployments (D-F8).
 */
public record ModelDetailsMcpDto(
        @Nullable ModelLimitsMcpDto limits, @Nullable ModelCapabilitiesMcpDto capabilities) {

    /** {@code service.domain.dto.deployment.ModelLimitsDto}'s MCP-owned mirror. */
    public record ModelLimitsMcpDto(
            @Nullable Integer maxTotalTokens, @Nullable Integer maxCompletionTokens) {}

    /**
     * {@code service.domain.dto.deployment.ModelCapabilitiesDto}'s MCP-owned mirror. Only the
     * three capabilities the umbrella spec names are exposed; {@code scaleTypes}, {@code fineTune}
     * and {@code inference} are operator-facing detail this version does not surface.
     */
    public record ModelCapabilitiesMcpDto(
            @Nullable Boolean chatCompletion,
            @Nullable Boolean completion,
            @Nullable Boolean embeddings) {}
}
