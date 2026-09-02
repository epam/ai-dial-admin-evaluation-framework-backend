package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

/**
 * Collapses the three parallel DIAL Core probes of a type-less by-ID lookup into a single outcome:
 * either the winning deployment payload, or one unified {@link DialCoreClientException}.
 *
 * <p>Exactly two exit paths:
 * <ul>
 *   <li><b>at least one hit</b> — returned; a multi-hit (an ID collision DIAL Core is not expected
 *       to produce) is resolved by the fixed type precedence
 *       {@code dial-model > dial-application > dial-toolset} and logged at WARN</li>
 *   <li><b>no hit</b> — the probe failures are unified into one exception whose upstream status is
 *       the highest-severity status observed, ordered {@code 401 > 403 > other > 404}, so an auth
 *       failure or an upstream outage is never reported as a not-found result merely because the
 *       other probes returned 404</li>
 * </ul>
 *
 * <p>Pure function of its inputs — no HTTP, no Spring context needed to exercise it.
 */
@Slf4j
@Component
@LogExecution
public class DeploymentProbeCollapser {

    /** Severity ranks; a higher rank wins when unifying probe failures. */
    private static final int SEVERITY_NOT_FOUND = 1;

    private static final int SEVERITY_OTHER = 2;
    private static final int SEVERITY_ACCESS_DENIED = 3;
    private static final int SEVERITY_AUTH = 4;

    /** Type precedence is the declaration order of {@code DeploymentType} — one source of truth. */
    private static final Comparator<DeploymentProbe> BY_TYPE_PRECEDENCE =
            Comparator.comparingInt(probe -> probe.type().ordinal());

    /** Label for a probe that answered 2xx with an empty body. */
    private static final String EMPTY_OUTCOME = "empty";

    /**
     * Resolves the winning payload, or throws the unified upstream failure when nothing was found.
     *
     * @param deploymentId the looked-up ID, used for logging and the error message
     * @param probes       outcomes of all per-type probes
     * @return the payload of the winning probe
     * @throws DialCoreClientException when no probe resolved the ID
     */
    public DialCoreDeploymentDto collapse(String deploymentId, List<DeploymentProbe> probes) {
        final List<DeploymentProbe> hits = probes.stream()
                .filter(DeploymentProbe::isHit)
                .sorted(BY_TYPE_PRECEDENCE)
                .toList();

        if (hits.isEmpty()) {
            throw unify(deploymentId, probes);
        }

        final DeploymentProbe winner = hits.getFirst();
        if (hits.size() > 1) {
            log.warn(
                    "Deployment ID '{}' resolved in several DIAL Core types ({}); returning '{}' by type precedence",
                    deploymentId,
                    typeValues(hits),
                    winner.type().getValue());
        }
        return winner.body();
    }

    private static DialCoreClientException unify(String deploymentId, List<DeploymentProbe> probes) {
        final HttpStatusCode status = probes.stream()
                .map(DeploymentProbe::error)
                .filter(Objects::nonNull)
                .map(DialCoreClientException::getStatusCode)
                .max(Comparator.comparingInt(DeploymentProbeCollapser::severity))
                .orElse(HttpStatus.NOT_FOUND);
        final String message = "Deployment '" + deploymentId + "' not resolvable in DIAL Core: " + outcomes(probes);
        log.debug("Unified deployment lookup failure ({}): {}", status.value(), message);
        return new DialCoreClientException(status, message);
    }

    private static int severity(HttpStatusCode status) {
        if (status == null) {
            return SEVERITY_OTHER;
        }
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            return SEVERITY_AUTH;
        }
        if (status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
            return SEVERITY_ACCESS_DENIED;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return SEVERITY_NOT_FOUND;
        }
        return SEVERITY_OTHER;
    }

    private static String outcomes(List<DeploymentProbe> probes) {
        return probes.stream()
                .sorted(BY_TYPE_PRECEDENCE)
                .map(probe -> probe.type().getValue() + "=" + outcome(probe))
                .collect(Collectors.joining(", "));
    }

    private static String outcome(DeploymentProbe probe) {
        if (probe.error() == null) {
            return EMPTY_OUTCOME;
        }
        final HttpStatusCode status = probe.error().getStatusCode();
        return status != null ? String.valueOf(status.value()) : EMPTY_OUTCOME;
    }

    private static String typeValues(List<DeploymentProbe> probes) {
        return probes.stream().map(probe -> probe.type().getValue()).collect(Collectors.joining(", "));
    }
}
