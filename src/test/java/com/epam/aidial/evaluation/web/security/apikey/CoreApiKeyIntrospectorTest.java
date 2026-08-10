package com.epam.aidial.evaluation.web.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@DisplayName("CoreApiKeyIntrospector")
class CoreApiKeyIntrospectorTest {

    private MockRestServiceServer server;
    private CoreApiKeyIntrospector introspector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://core");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApiKeyProperties properties = new ApiKeyProperties(new ObjectMapper());
        properties.setCoreUrl("http://core");
        properties.setUserClaimsRoleClaim("roles");
        properties.setStartupProbe(true);

        JwtSecurityProperties jwtSecurityProperties = new JwtSecurityProperties();
        jwtSecurityProperties.setUserClaim("sub");

        introspector = new CoreApiKeyIntrospector(restClient, properties, jwtSecurityProperties);
    }

    @Test
    @DisplayName("shouldReturnProjectKeyResultOnSuccess")
    void shouldReturnProjectKeyResultOnSuccess() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andExpect(header("Api-Key", "key-1"))
                .andRespond(
                        withSuccess("{\"roles\":[\"admin\"],\"project\":\"my-project\"}", MediaType.APPLICATION_JSON));

        IntrospectionResult result = introspector.introspect("key-1");

        assertThat(result.principal()).isEqualTo("my-project");
        assertThat(result.rawRoles()).containsExactly("admin");
        assertThat(result.fromProjectKey()).isTrue();
    }

    @Test
    @DisplayName("shouldReturnJwtRootedResultWhenResponseHasUserClaims")
    void shouldReturnJwtRootedResultWhenResponseHasUserClaims() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess(
                        "{\"roles\":[],\"userClaims\":{\"sub\":[\"user-1\"],\"roles\":[\"viewer\",\"admin\"]}}",
                        MediaType.APPLICATION_JSON));

        IntrospectionResult result = introspector.introspect("key-2");

        assertThat(result.principal()).isEqualTo("user-1");
        assertThat(result.rawRoles()).containsExactlyInAnyOrder("viewer", "admin");
        assertThat(result.fromProjectKey()).isFalse();
    }

    @Test
    @DisplayName("shouldAcceptUserClaimsWithScalarValues")
    void shouldAcceptUserClaimsWithScalarValues() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess(
                        "{\"userClaims\":{\"sub\":\"user-1\",\"roles\":\"admin\"}}", MediaType.APPLICATION_JSON));

        IntrospectionResult result = introspector.introspect("key-3");

        assertThat(result.principal()).isEqualTo("user-1");
        assertThat(result.rawRoles()).containsExactly("admin");
    }

    @Test
    @DisplayName("shouldPreferProjectOverUserClaimsWhenBothPresent")
    void shouldPreferProjectOverUserClaimsWhenBothPresent() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess(
                        "{\"roles\":[\"admin\"],\"project\":\"my-project\",\"userClaims\":{\"sub\":[\"user-1\"]}}",
                        MediaType.APPLICATION_JSON));

        IntrospectionResult result = introspector.introspect("key-4");

        assertThat(result.fromProjectKey()).isTrue();
        assertThat(result.principal()).isEqualTo("my-project");
    }

    @Test
    @DisplayName("shouldThrowBadCredentialsOnHttp401")
    void shouldThrowBadCredentialsOnHttp401() {
        server.expect(requestTo("http://core/v1/user/info")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> introspector.introspect("bad-key")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("shouldThrowBadCredentialsWhenResponseHasNeitherProjectNorUserClaims")
    void shouldThrowBadCredentialsWhenResponseHasNeitherProjectNorUserClaims() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess("{\"roles\":[\"admin\"]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> introspector.introspect("key-5")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("shouldThrowBadCredentialsWhenUserClaimsLacksPrincipalClaim")
    void shouldThrowBadCredentialsWhenUserClaimsLacksPrincipalClaim() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess("{\"userClaims\":{\"roles\":[\"admin\"]}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> introspector.introspect("key-6")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("shouldThrowBadCredentialsOnEmptyUserClaims")
    void shouldThrowBadCredentialsOnEmptyUserClaims() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess("{\"userClaims\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> introspector.introspect("key-7")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("shouldThrowAuthenticationServiceExceptionOnConnectionFailure")
    void shouldThrowAuthenticationServiceExceptionOnConnectionFailure() {
        server.expect(requestTo("http://core/v1/user/info")).andRespond(request -> {
            throw new IOException(new SocketTimeoutException("timeout"));
        });

        assertThatThrownBy(() -> introspector.introspect("key-8")).isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    @DisplayName("shouldTolerateMissingRolesField")
    void shouldTolerateMissingRolesField() {
        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess("{\"project\":\"my-project\"}", MediaType.APPLICATION_JSON));

        IntrospectionResult result = introspector.introspect("key-9");

        assertThat(result.rawRoles()).isEmpty();
    }

    @Test
    @DisplayName("probeShouldSucceedWhenCoreRespondsWith4xx")
    void probeShouldSucceedWhenCoreRespondsWith4xx() {
        server.expect(requestTo("http://core/v1/user/info")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        introspector.probeCore();

        server.verify();
    }

    @Test
    @DisplayName("probeShouldFailWhenCoreUnreachable")
    void probeShouldFailWhenCoreUnreachable() {
        server.expect(requestTo("http://core/v1/user/info")).andRespond(request -> {
            throw new IOException(new SocketTimeoutException("timeout"));
        });

        assertThatThrownBy(() -> introspector.probeCore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("probeShouldFailWhenCoreResponds5xx")
    void probeShouldFailWhenCoreResponds5xx() {
        server.expect(requestTo("http://core/v1/user/info")).andRespond(withServerError());

        assertThatThrownBy(() -> introspector.probeCore()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("probeShouldNoOpWhenDisabled")
    void probeShouldNoOpWhenDisabled() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://core");
        MockRestServiceServer disabledProbeServer =
                MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApiKeyProperties properties = new ApiKeyProperties(new ObjectMapper());
        properties.setCoreUrl("http://core");
        properties.setUserClaimsRoleClaim("roles");
        properties.setStartupProbe(false);

        JwtSecurityProperties jwtSecurityProperties = new JwtSecurityProperties();
        jwtSecurityProperties.setUserClaim("sub");

        CoreApiKeyIntrospector disabledProbeIntrospector =
                new CoreApiKeyIntrospector(restClient, properties, jwtSecurityProperties);

        disabledProbeIntrospector.probeCore();

        disabledProbeServer.verify();
    }
}
