package com.epam.aidial.evaluation.runner.model.target;

import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;

/**
 * Derives a {@link SuiteTarget} from the flat wire/snapshot representations.
 *
 * <p>Static on purpose: pure, dependency-free derivation over runner DTOs, in the same style as
 * {@link SuiteType#fromValue(String)}.
 *
 * <p>The only null-tolerant seams are {@link #fromSnapshot} (old snapshot JSON) and {@link #fromSuiteResponse}
 * (older remote server); everywhere else the suite type is non-null.
 */
public final class SuiteTargets {

    private SuiteTargets() {}

    /** Response from a server predating {@code suiteType}: a missing type means DEPLOYMENT. */
    private static SuiteType orDefault(SuiteType type) {
        return type != null ? type : SuiteType.DEPLOYMENT;
    }

    /** Derives the target from a persisted snapshot, whose {@code suiteType} is a nullable String. */
    public static SuiteTarget fromSnapshot(SuiteSnapshotDto snapshot) {
        final SuiteType type =
                snapshot.getSuiteType() != null ? SuiteType.valueOf(snapshot.getSuiteType()) : SuiteType.DEPLOYMENT;
        return switch (type) {
            case DEPLOYMENT -> new DeploymentTarget(snapshot.getDeploymentRef());
            case ROUTE -> new RouteTarget(snapshot.getRoutesRef());
            case MCP_TOOL -> new McpToolTarget(snapshot.getMcpDeploymentRef(), snapshot.getToolRef());
        };
    }

    /** Derives the target from a suite response DTO. */
    public static SuiteTarget fromSuiteResponse(TestSuiteResponseDto suite) {
        return switch (orDefault(suite.getSuiteType())) {
            case DEPLOYMENT -> new DeploymentTarget(suite.getDeploymentRef());
            case ROUTE -> new RouteTarget(suite.getRoutesRef());
            case MCP_TOOL -> new McpToolTarget(suite.getMcpDeploymentRef(), suite.getToolRef());
        };
    }
}
