package com.epam.aidial.evaluation.configuration.properties.dialapp;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for DIAL Application Routes mode. When enabled, eval runs are triggered via a DIAL Core
 * Application Route (using the user's JWT), and DIAL Core's per-request key (PRK) is used for deployment
 * invocations instead of the user's JWT.
 *
 * <p>Unconditional (unlike the other DIAL App mode beans): it must exist and validate the flag
 * combination regardless of {@code dial-app-proxy.enabled}'s own value (design.md Decision 4).
 *
 * <p><b>Prerequisite:</b> `config.rest.security.api-key.enabled=true` is required because the internal
 * endpoint (`POST /api/internal/runs/{runId}/execute`) is authenticated via the DIAL API-Key chain
 * (the same as any other API-Key caller), not by a separate mechanism. {@link ApiKeyProperties} is itself
 * conditional on that property, so it is resolved lazily via {@link ObjectProvider} — a plain constructor
 * dependency would fail bean creation with an opaque {@code UnsatisfiedDependencyException} instead of this
 * class's own clear fail-fast message when API-Key auth is disabled.
 */
@Getter
@Setter
@Slf4j
@Component
@Validated
@LogExecution
@ConfigurationProperties(prefix = "dial-app-proxy")
public class DialAppProperties {

    private boolean enabled;
    private String deploymentName;
    private long heartbeatIntervalMs;
    private long triggerReadTimeoutMs;

    private final ObjectProvider<ApiKeyProperties> apiKeyProperties;

    public DialAppProperties(ObjectProvider<ApiKeyProperties> apiKeyProperties) {
        this.apiKeyProperties = apiKeyProperties;
    }

    @PostConstruct
    public void validate() {
        if (!enabled) {
            log.debug("DIAL App mode is disabled");
            return;
        }

        ApiKeyProperties resolvedApiKeyProperties = apiKeyProperties.getIfAvailable();
        if (resolvedApiKeyProperties == null || !resolvedApiKeyProperties.isEnabled()) {
            throw new IllegalStateException(
                    "dial-app-proxy.enabled=true requires config.rest.security.api-key.enabled=true. "
                            + "The internal eval endpoint (/api/internal/runs/{runId}/execute) is authenticated by the "
                            + "DIAL API-Key auth chain; it has no other authentication mechanism.");
        }

        log.info(
                "DIAL App mode is enabled. Deployment name: {}, heartbeat interval: {}ms, trigger read timeout: {}ms",
                deploymentName,
                heartbeatIntervalMs,
                triggerReadTimeoutMs);
    }
}
