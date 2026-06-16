package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class AuthorResolver {

    private final JwtSecurityProperties jwtSecurityProperties;

    /**
     * Resolves user identity for createdBy attribution from JWT.
     * In oidc mode a valid JWT with the configured claim is required (401 if missing).
     * In none mode JWT is null and "anonymous" is returned.
     */
    public String getCreatedBy(Jwt jwt) {
        if (jwt == null) {
            return "anonymous";
        }
        String claim = jwtSecurityProperties.getUserClaim();
        Object value = jwt.getClaim(claim);
        return value != null ? value.toString() : "anonymous";
    }
}
