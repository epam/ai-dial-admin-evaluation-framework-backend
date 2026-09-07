package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorResolver")
class AuthorResolverTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private DialCoreClient dialCoreClient;

    private JwtSecurityProperties properties;
    private AuthorResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new JwtSecurityProperties();
        properties.setUserClaim("sub");
        resolver = new AuthorResolver(properties, dialCoreClient);
    }

    @Test
    @DisplayName("returns 'anonymous' and never calls Core when JWT is null")
    void anonymousWhenJwtNull() {
        properties.setResolveUserName(true);

        assertThat(resolver.getCreatedBy(null)).isEqualTo("anonymous");
        verify(dialCoreClient, never()).getUserInfo();
    }

    @Test
    @DisplayName("returns 'anonymous' and never calls Core when configured claim is missing")
    void anonymousWhenClaimMissing() {
        properties.setResolveUserName(true);

        assertThat(resolver.getCreatedBy(jwtWithClaims(Map.of("email", "jane@example.com"))))
                .isEqualTo("anonymous");
        verify(dialCoreClient, never()).getUserInfo();
    }

    @Test
    @DisplayName("returns raw claim value and never calls Core when resolve-user-name is disabled")
    void claimValueWhenResolutionDisabled() {
        properties.setResolveUserName(false);

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("user-123");
        verify(dialCoreClient, never()).getUserInfo();
    }

    @Test
    @DisplayName("returns userDisplayName from Core when resolve-user-name is enabled")
    void displayNameWhenResolutionEnabled() {
        properties.setResolveUserName(true);
        when(dialCoreClient.getUserInfo())
                .thenReturn(OBJECT_MAPPER.readTree("{\"sub\":\"user-123\",\"userDisplayName\":\"Jane Doe\"}"));

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("Jane Doe");
    }

    @Test
    @DisplayName("falls back to claim value when userDisplayName is blank")
    void claimValueWhenDisplayNameBlank() {
        properties.setResolveUserName(true);
        when(dialCoreClient.getUserInfo()).thenReturn(OBJECT_MAPPER.readTree("{\"userDisplayName\":\"  \"}"));

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("user-123");
    }

    @Test
    @DisplayName("falls back to claim value when userDisplayName is absent or not a string")
    void claimValueWhenDisplayNameAbsent() {
        properties.setResolveUserName(true);
        when(dialCoreClient.getUserInfo()).thenReturn(OBJECT_MAPPER.readTree("{\"userDisplayName\":null}"));

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("user-123");
    }

    @Test
    @DisplayName("falls back to claim value when Core returns no body")
    void claimValueWhenCoreBodyEmpty() {
        properties.setResolveUserName(true);
        when(dialCoreClient.getUserInfo()).thenReturn(null);

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("user-123");
    }

    @Test
    @DisplayName("falls back to claim value when Core responds with an error")
    void claimValueWhenCoreFails() {
        properties.setResolveUserName(true);
        when(dialCoreClient.getUserInfo())
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(401), "Unauthorized"));

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("user-123");
    }

    @Test
    @DisplayName("falls back to claim value when Core is unreachable")
    void claimValueWhenCoreUnreachable() {
        properties.setResolveUserName(true);
        when(dialCoreClient.getUserInfo()).thenThrow(new ResourceAccessException("connection refused"));

        assertThat(resolver.getCreatedBy(jwtWithSub("user-123"))).isEqualTo("user-123");
    }

    private static Jwt jwtWithSub(String sub) {
        return jwtWithClaims(Map.of("sub", sub));
    }

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
