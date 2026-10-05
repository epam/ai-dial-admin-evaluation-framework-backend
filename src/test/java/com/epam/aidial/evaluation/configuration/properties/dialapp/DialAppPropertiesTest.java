package com.epam.aidial.evaluation.configuration.properties.dialapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DialAppProperties")
class DialAppPropertiesTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ApiKeyProperties> apiKeyPropertiesProvider = mock(ObjectProvider.class);

    private DialAppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DialAppProperties(apiKeyPropertiesProvider);
    }

    @Test
    @DisplayName("bindsConfiguredValues")
    void bindsConfiguredValues() {
        properties.setEnabled(true);
        properties.setDeploymentName("EF");
        properties.setHeartbeatIntervalMs(30000L);
        properties.setTriggerReadTimeoutMs(43200000L);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDeploymentName()).isEqualTo("EF");
        assertThat(properties.getHeartbeatIntervalMs()).isEqualTo(30000L);
        assertThat(properties.getTriggerReadTimeoutMs()).isEqualTo(43200000L);
    }

    @Test
    @DisplayName("shouldNoOpWhenDisabled")
    void shouldNoOpWhenDisabled() {
        properties.setEnabled(false);

        assertThatCode(() -> properties.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNoOpWhenDisabledEvenIfApiKeyAuthDisabled")
    void shouldNoOpWhenDisabledEvenIfApiKeyAuthDisabled() {
        properties.setEnabled(false);
        ApiKeyProperties apiKeyProperties = new ApiKeyProperties(new ObjectMapper());
        apiKeyProperties.setEnabled(false);
        when(apiKeyPropertiesProvider.getIfAvailable()).thenReturn(apiKeyProperties);

        assertThatCode(() -> properties.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldRejectWhenEnabledAndApiKeyPropertiesBeanAbsent")
    void shouldRejectWhenEnabledAndApiKeyPropertiesBeanAbsent() {
        properties.setEnabled(true);
        when(apiKeyPropertiesProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dial-app-proxy.enabled")
                .hasMessageContaining("config.rest.security.api-key.enabled");
    }

    @Test
    @DisplayName("shouldRejectWhenEnabledAndApiKeyAuthDisabled")
    void shouldRejectWhenEnabledAndApiKeyAuthDisabled() {
        properties.setEnabled(true);
        ApiKeyProperties apiKeyProperties = new ApiKeyProperties(new ObjectMapper());
        apiKeyProperties.setEnabled(false);
        when(apiKeyPropertiesProvider.getIfAvailable()).thenReturn(apiKeyProperties);

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dial-app-proxy.enabled")
                .hasMessageContaining("config.rest.security.api-key.enabled");
    }

    @Test
    @DisplayName("shouldPassWhenEnabledAndApiKeyAuthEnabled")
    void shouldPassWhenEnabledAndApiKeyAuthEnabled() {
        properties.setEnabled(true);
        properties.setDeploymentName("EF");
        properties.setHeartbeatIntervalMs(30000L);
        properties.setTriggerReadTimeoutMs(43200000L);
        ApiKeyProperties apiKeyProperties = new ApiKeyProperties(new ObjectMapper());
        apiKeyProperties.setEnabled(true);
        apiKeyProperties.setCoreUrl("http://core");
        apiKeyProperties.setRolesMapping("{\"admin\":[\"admin\"]}");
        apiKeyProperties.validate();
        when(apiKeyPropertiesProvider.getIfAvailable()).thenReturn(apiKeyProperties);

        assertThatCode(() -> properties.validate()).doesNotThrowAnyException();
    }
}
