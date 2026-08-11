package com.epam.aidial.evaluation.web.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ApiKeyAuthorityResolver")
class ApiKeyAuthorityResolverTest {

    private ApiKeyAuthorityResolver resolver;

    @BeforeEach
    void setUp() {
        ApiKeyProperties properties = new ApiKeyProperties(new ObjectMapper());
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setRolesMapping("{\"admin\":[\"admin\"],\"default\":[\"viewer\"]}");
        properties.setDefaultRolesMapping("{\"ConfigAdmin\":[\"admin\"]}");
        properties.validate();

        resolver = new ApiKeyAuthorityResolver(properties);
    }

    @Test
    @DisplayName("shouldMapProjectKeyRolesViaRolesMapping")
    void shouldMapProjectKeyRolesViaRolesMapping() {
        var authorities = resolver.resolve(List.of("admin"), true);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("admin");
    }

    @Test
    @DisplayName("shouldMapJwtRootedRolesViaDefaultRolesMapping")
    void shouldMapJwtRootedRolesViaDefaultRolesMapping() {
        var authorities = resolver.resolve(List.of("ConfigAdmin"), false);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("admin");
    }

    @Test
    @DisplayName("shouldNotCrossOverMappings")
    void shouldNotCrossOverMappings() {
        assertThat(resolver.resolve(List.of("ConfigAdmin"), true)).isEmpty();
        assertThat(resolver.resolve(List.of("admin"), false)).isEmpty();
    }

    @Test
    @DisplayName("shouldReturnEmptyForUnmappedRoles")
    void shouldReturnEmptyForUnmappedRoles() {
        assertThat(resolver.resolve(List.of("unknown-role"), true)).isEmpty();
        assertThat(resolver.resolve(List.of("unknown-role"), false)).isEmpty();
    }

    @Test
    @DisplayName("shouldHandleEmptyRoleList")
    void shouldHandleEmptyRoleList() {
        assertThat(resolver.resolve(List.of(), true)).isEmpty();
        assertThat(resolver.resolve(List.of(), false)).isEmpty();
    }
}
