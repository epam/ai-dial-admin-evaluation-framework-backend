package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class AuthorResolver {

    static final String ANONYMOUS = "anonymous";
    static final String USER_DISPLAY_NAME_FIELD = "userDisplayName";

    private final JwtSecurityProperties jwtSecurityProperties;
    private final DialCoreClient dialCoreClient;

    /**
     * Resolves user identity for createdBy attribution from JWT.
     * In oidc mode a valid JWT with the configured claim is required (401 if missing).
     * In none mode JWT is null and "anonymous" is returned.
     *
     * <p>When {@code security.jwt.resolve-user-name} is enabled, DIAL Core's user-info endpoint is
     * consulted for a human-readable {@code userDisplayName}; the raw claim value remains the
     * fallback when the name is absent or Core is unreachable. Graceful degradation is acceptable
     * here because attribution is informational, not a data-integrity concern.
     */
    public String getCreatedBy(Jwt jwt) {
        if (jwt == null) {
            return ANONYMOUS;
        }

        final String claim = jwtSecurityProperties.getUserClaim();
        final Object value = jwt.getClaim(claim);
        if (value == null) {
            return ANONYMOUS;
        }

        final String userId = value.toString();
        if (!jwtSecurityProperties.isResolveUserName()) {
            return userId;
        }

        final String displayName = resolveDisplayName(userId);
        return displayName != null ? displayName : userId;
    }

    private String resolveDisplayName(String userId) {
        try {
            return tryResolveDisplayName(userId);
        } catch (DialCoreClientException | RestClientException e) {
            log.warn(
                    "Failed to resolve display name for user '{}' via DIAL Core; using claim value: {}",
                    userId,
                    e.getMessage(),
                    e);
            return null;
        }
    }

    private @Nullable String tryResolveDisplayName(String userId) {
        final JsonNode userInfo = dialCoreClient.getUserInfo();
        if (userInfo == null) {
            log.debug("DIAL Core user-info returned no body for user '{}'; using claim value", userId);
            return null;
        }

        final JsonNode nameNode = userInfo.get(USER_DISPLAY_NAME_FIELD);
        final String displayName = nameNode != null && nameNode.isString() ? nameNode.asString() : null;
        if (StringUtils.isBlank(displayName)) {
            log.debug(
                    "DIAL Core user-info has no '{}' for user '{}'; using claim value",
                    USER_DISPLAY_NAME_FIELD,
                    userId);
            return null;
        }

        return displayName;
    }
}
