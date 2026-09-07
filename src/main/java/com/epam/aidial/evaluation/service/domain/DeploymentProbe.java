package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentType;

/**
 * Outcome of one per-type DIAL Core lookup issued by the type-less by-ID probe
 * ({@code GET /api/v1/deployments/all/**}).
 *
 * <p>Three shapes, distinguished by which fields are set:
 * <ul>
 *   <li><b>hit</b> — {@code body != null}: DIAL Core resolved the ID as this type</li>
 *   <li><b>miss</b> — both null: DIAL Core answered 2xx with an empty body</li>
 *   <li><b>error</b> — {@code error != null}: DIAL Core answered a non-2xx status</li>
 * </ul>
 *
 * <p>Internal orchestration carrier between {@link DeploymentService} and
 * {@link DeploymentProbeCollapser} — never serialized to a client.
 *
 * @param type  the deployment type this probe asked DIAL Core about
 * @param body  the resolved payload, or {@code null} on a miss or error
 * @param error the upstream failure, or {@code null} on a hit or miss
 */
public record DeploymentProbe(DeploymentType type, DialCoreDeploymentDto body, DialCoreClientException error) {

    /** A probe that completed; {@code body} may be null, which makes it a miss. */
    public static DeploymentProbe completed(DeploymentType type, DialCoreDeploymentDto body) {
        return new DeploymentProbe(type, body, null);
    }

    /** A probe that failed with an upstream status. */
    public static DeploymentProbe failed(DeploymentType type, DialCoreClientException error) {
        return new DeploymentProbe(type, null, error);
    }

    public boolean isHit() {
        return body != null;
    }
}
