package com.epam.aidial.evaluation.web.security.apikey;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Maps DIAL Core's raw role names to this service's authority strings, using an independent
 * mapping for the project-key introspection shape versus the JWT-rooted per-request-key shape.
 * Unmapped role names are dropped rather than rejected, matching this service's existing
 * OIDC "unmapped role -&gt; dropped" behavior in {@code SecurityConfiguration}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(value = "config.rest.security.api-key.enabled", havingValue = "true")
public class ApiKeyAuthorityResolver {

    private final ApiKeyProperties properties;

    public Collection<? extends GrantedAuthority> resolve(List<String> rawRoles, boolean fromProjectKey) {
        Map<String, List<String>> mapping =
                fromProjectKey ? properties.getParsedRolesMapping() : properties.getParsedDefaultRolesMapping();
        return rawRoles.stream()
                .flatMap(role -> mapping.getOrDefault(role, List.of()).stream())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
