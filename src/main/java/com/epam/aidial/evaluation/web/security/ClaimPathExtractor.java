package com.epam.aidial.evaluation.web.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared, stateless claim-path extraction used to resolve role values out of a claims map by one or
 * more dot-separated paths (e.g. {@code "resource_access.dial-core.roles"}). Used by {@link
 * MultiPathGrantedAuthoritiesConverter} (bearer-JWT authorities, keyed by {@code Jwt.getClaims()}) and
 * by {@code CoreApiKeyIntrospector} (DIAL Core introspection's raw {@code userClaims} map), so the two
 * authentication paths resolve per-issuer role claims identically.
 *
 * <p>Deliberately a plain static-method utility, not a Spring bean: neither caller holds it as an
 * injected dependency ({@link MultiPathGrantedAuthoritiesConverter} is manually {@code new}'d per
 * provider by {@code JwtAuthenticationConverterFactory}), so there is nothing to wire it into.
 */
@Slf4j
public final class ClaimPathExtractor {

    private ClaimPathExtractor() {}

    /** Extracts the value at a dot-separated nested path (e.g. "a.b.c") out of a claims map. */
    public static Object extractPath(String path, Map<?, ?> claims) {
        Object current = claims;
        for (String partPath : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                log.warn("Claim path '{}' failed at '{}'", path, partPath);
                return null;
            }
            current = map.get(partPath);
        }
        if (current == null) {
            log.warn("Claim path '{}' extracted as null", path);
        }
        return current;
    }

    /** Unions all String/{@code Collection<String>} values extracted by {@code paths} from {@code claims}. */
    public static Set<String> extractRoles(List<String> paths, Map<?, ?> claims) {
        Set<String> roles = new HashSet<>();
        for (String path : paths) {
            Object value = extractPath(path, claims);
            if (value != null) {
                addRoleValues(value, roles);
            }
        }
        return roles;
    }

    private static void addRoleValues(Object value, Set<String> roles) {
        if (value instanceof String role) {
            roles.add(role);
        } else if (value instanceof Collection<?> listRoles) {
            for (Object role : listRoles) {
                if (role instanceof String roleString) {
                    roles.add(roleString);
                } else {
                    log.warn("Unsupported authority value type: {}", role.getClass());
                }
            }
        }
    }
}
