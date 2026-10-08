package com.epam.aidial.evaluation.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClaimPathExtractor")
class ClaimPathExtractorTest {

    @Test
    @DisplayName("extracts a top-level string claim")
    void extractsTopLevelStringClaim() {
        Map<String, Object> claims = Map.of("sub", "user-1");

        Object value = ClaimPathExtractor.extractPath("sub", claims);

        assertThat(value).isEqualTo("user-1");
    }

    @Test
    @DisplayName("extracts a top-level list claim")
    void extractsTopLevelListClaim() {
        Map<String, Object> claims = Map.of("roles", List.of("admin", "viewer"));

        Object value = ClaimPathExtractor.extractPath("roles", claims);

        assertThat(value).isEqualTo(List.of("admin", "viewer"));
    }

    @Test
    @DisplayName("extracts a nested dot-separated path")
    void extractsNestedDotPath() {
        Map<String, Object> claims = Map.of("resource_access", Map.of("dial-core", Map.of("roles", List.of("admin"))));

        Object value = ClaimPathExtractor.extractPath("resource_access.dial-core.roles", claims);

        assertThat(value).isEqualTo(List.of("admin"));
    }

    @Test
    @DisplayName("returns null and does not throw when a path fails partway through")
    void returnsNullWhenPathFailsPartway() {
        Map<String, Object> claims = Map.of("sub", "user-1");

        Object value = ClaimPathExtractor.extractPath("sub.nested", claims);

        assertThat(value).isNull();
    }

    @Test
    @DisplayName("extractRoles unions values across multiple paths and dedupes")
    void extractRolesUnionsAcrossPaths() {
        Map<String, Object> claims = Map.of(
                "dial_roles", List.of("admin", "viewer"),
                "roles", List.of("viewer", "editor"));

        Set<String> roles = ClaimPathExtractor.extractRoles(List.of("dial_roles", "roles"), claims);

        assertThat(roles).containsExactlyInAnyOrder("admin", "viewer", "editor");
    }

    @Test
    @DisplayName("extractRoles skips non-String entries in a list claim")
    void extractRolesSkipsNonStringListEntries() {
        Map<String, Object> claims = Map.of("roles", List.of("admin", 42));

        Set<String> roles = ClaimPathExtractor.extractRoles(List.of("roles"), claims);

        assertThat(roles).containsExactly("admin");
    }

    @Test
    @DisplayName("extractRoles ignores a missing path without throwing")
    void extractRolesIgnoresMissingPath() {
        Map<String, Object> claims = Map.of("sub", "user-1");

        Set<String> roles = ClaimPathExtractor.extractRoles(List.of("roles"), claims);

        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("extractRoleValues normalizes an already-extracted String value")
    void extractRoleValuesNormalizesStringValue() {
        List<String> roles = ClaimPathExtractor.extractRoleValues("admin");

        assertThat(roles).containsExactly("admin");
    }

    @Test
    @DisplayName("extractRoleValues normalizes an already-extracted collection, skipping non-String entries")
    void extractRoleValuesNormalizesCollectionValue() {
        List<String> roles = ClaimPathExtractor.extractRoleValues(List.of("admin", "viewer"));

        assertThat(roles).containsExactlyInAnyOrder("admin", "viewer");
    }

    @Test
    @DisplayName("extractRoleValues returns empty for a null value")
    void extractRoleValuesReturnsEmptyForNull() {
        List<String> roles = ClaimPathExtractor.extractRoleValues(null);

        assertThat(roles).isEmpty();
    }
}
