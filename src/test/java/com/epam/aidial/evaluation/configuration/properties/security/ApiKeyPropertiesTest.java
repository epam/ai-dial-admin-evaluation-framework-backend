package com.epam.aidial.evaluation.configuration.properties.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ApiKeyProperties")
class ApiKeyPropertiesTest {

    private ApiKeyProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ApiKeyProperties(new ObjectMapper());
    }

    @Test
    @DisplayName("shouldNoOpWhenDisabled")
    void shouldNoOpWhenDisabled() {
        properties.setEnabled(false);

        properties.validate();

        assertThat(properties.getParsedKeyRolesMapping()).isEmpty();
        assertThat(properties.getParsedJwtRolesMapping()).isEmpty();
    }

    @Test
    @DisplayName("shouldRejectMissingCoreUrlWhenEnabled")
    void shouldRejectMissingCoreUrlWhenEnabled() {
        properties.setEnabled(true);
        properties.setCoreUrl("");
        properties.setKeyRolesMapping("{\"admin\":[\"admin\"]}");

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-url");
    }

    @Test
    @DisplayName("shouldRejectInvalidRolesMappingJson")
    void shouldRejectInvalidRolesMappingJson() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setKeyRolesMapping("not-json");

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config.rest.security.api-key.key-roles-mapping");
    }

    @Test
    @DisplayName("shouldRejectInvalidJwtRolesMappingJson")
    void shouldRejectInvalidJwtRolesMappingJson() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setJwtRolesMapping("not-json");

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config.rest.security.api-key.jwt-roles-mapping");
    }

    @Test
    @DisplayName("shouldRejectWhenBothMappingsAreBlank")
    void shouldRejectWhenBothMappingsAreBlank() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roles-mapping");
    }

    @Test
    @DisplayName("shouldRejectWhenBothMappingsAreEmptyObjects")
    void shouldRejectWhenBothMappingsAreEmptyObjects() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setKeyRolesMapping("{}");
        properties.setJwtRolesMapping("{}");

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roles-mapping");
    }

    @Test
    @DisplayName("shouldParseRolesMappingOnly")
    void shouldParseRolesMappingOnly() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setKeyRolesMapping("{\"admin\":[\"admin\"]}");

        properties.validate();

        assertThat(properties.getParsedKeyRolesMapping()).containsExactly(Map.entry("admin", List.of("admin")));
        assertThat(properties.getParsedJwtRolesMapping()).isEmpty();
    }

    @Test
    @DisplayName("shouldAcceptBlankRolesMappingWhenDefaultMappingIsSet")
    void shouldAcceptBlankRolesMappingWhenDefaultMappingIsSet() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setJwtRolesMapping("{\"ConfigAdmin\":[\"admin\"]}");

        properties.validate();

        assertThat(properties.getParsedKeyRolesMapping()).isEmpty();
        assertThat(properties.getParsedJwtRolesMapping()).containsExactly(Map.entry("ConfigAdmin", List.of("admin")));
    }

    @Test
    @DisplayName("shouldParseBothMappingsWhenProvided")
    void shouldParseBothMappingsWhenProvided() {
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setKeyRolesMapping("{\"admin\":[\"admin\"]}");
        properties.setJwtRolesMapping("{\"ConfigAdmin\":[\"admin\"]}");

        properties.validate();

        assertThat(properties.getParsedKeyRolesMapping()).containsExactly(Map.entry("admin", List.of("admin")));
        assertThat(properties.getParsedJwtRolesMapping()).containsExactly(Map.entry("ConfigAdmin", List.of("admin")));
    }
}
