package com.epam.aidial.evaluation.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("MultiPathGrantedAuthoritiesConverter")
class MultiPathGrantedAuthoritiesConverterTest {

    @Test
    @DisplayName("returns the union of all configured claim paths with the authority prefix applied")
    void returnsUnionOfConfiguredPathsWithPrefix() {
        Jwt jwt = jwtWithClaims(Map.of(
                "dial_roles", List.of("admin"),
                "roles", List.of("viewer", "admin")));

        MultiPathGrantedAuthoritiesConverter converter = new MultiPathGrantedAuthoritiesConverter();
        converter.setAuthoritiesPaths(List.of("dial_roles", "roles"));
        converter.setAuthorityPrefix("SCOPE_");

        List<String> authorities = converter.convert(jwt).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).containsExactlyInAnyOrder("SCOPE_admin", "SCOPE_viewer");
    }

    @Test
    @DisplayName("resolves a nested dot-separated claim path")
    void resolvesNestedClaimPath() {
        Jwt jwt = jwtWithClaims(Map.of("resource_access", Map.of("dial-core", Map.of("roles", List.of("admin")))));

        MultiPathGrantedAuthoritiesConverter converter = new MultiPathGrantedAuthoritiesConverter();
        converter.setAuthoritiesPaths(List.of("resource_access.dial-core.roles"));
        converter.setAuthorityPrefix("");

        List<String> authorities = converter.convert(jwt).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).containsExactly("admin");
    }

    @Test
    @DisplayName("returns no authorities when none of the configured paths resolve")
    void returnsEmptyWhenNoPathResolves() {
        Jwt jwt = jwtWithClaims(Map.of("sub", "user-1"));

        MultiPathGrantedAuthoritiesConverter converter = new MultiPathGrantedAuthoritiesConverter();
        converter.setAuthoritiesPaths(List.of("roles"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithClaims(Map<String, Object> extraClaims) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .claims(claims -> claims.putAll(extraClaims))
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(3600))
                .build();
    }
}
