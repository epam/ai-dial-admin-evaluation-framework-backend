package com.epam.aidial.evaluation.runner.model.target;

import com.epam.aidial.evaluation.runner.model.SuiteType;

/**
 * Internal, derived view of what a test suite invokes. Never serialized: wire DTOs, DB columns and persisted
 * snapshot JSON keep the flat {@code suiteType} + reference fields, and this type is derived from them at the
 * boundaries (see {@link SuiteTargets}).
 *
 * <p>Two-level split: most code only cares whether the target is HTTP ({@link HttpTarget}) or MCP
 * ({@link McpToolTarget}); only URL building and request-model validation care about
 * {@link DeploymentTarget} vs {@link RouteTarget}.
 *
 * <p>Implementations do not null-check their components: snapshots are trusted persisted data, and
 * request-boundary validation lives elsewhere.
 */
public sealed interface SuiteTarget permits HttpTarget, McpToolTarget {

    /** The {@link SuiteType} discriminator matching this target. */
    SuiteType type();
}
