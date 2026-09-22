package com.epam.aidial.evaluation.mcp.model;

import org.jspecify.annotations.Nullable;

/**
 * Type-specific detail nested under {@link DeploymentMcpDto#application()} for
 * {@link DeploymentKind#DIAL_APPLICATION} deployments (D-F8). Routes, pricing, icon URLs and
 * input attachment types are operator-facing detail this version does not surface.
 */
public record ApplicationDetailsMcpDto(@Nullable String applicationTypeSchemaId) {}
