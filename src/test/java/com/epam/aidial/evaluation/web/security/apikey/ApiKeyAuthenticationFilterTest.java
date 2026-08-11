package com.epam.aidial.evaluation.web.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ApiKeyAuthenticationFilter")
class ApiKeyAuthenticationFilterTest {

    private CoreApiKeyIntrospector introspector;
    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        ApiKeyProperties properties = new ApiKeyProperties(new ObjectMapper());
        properties.setEnabled(true);
        properties.setCoreUrl("http://core");
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxSize(100);
        properties.setRolesMapping("{\"admin\":[\"admin\"]}");
        properties.setDefaultRolesMapping("{\"ConfigAdmin\":[\"admin\"]}");
        properties.validate();

        introspector = mock(CoreApiKeyIntrospector.class);
        ApiKeyCache cache = new ApiKeyCache(properties);
        ApiKeyAuthorityResolver authorityResolver = new ApiKeyAuthorityResolver(properties);

        filter = new ApiKeyAuthenticationFilter(introspector, cache, authorityResolver, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("shouldPassThroughWhenAuthorizationHeaderPresent")
    void shouldPassThroughWhenAuthorizationHeaderPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer some-jwt");
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(introspector);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("shouldPassThroughWhenApiKeyBlank")
    void shouldPassThroughWhenApiKeyBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(introspector);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("shouldAuthenticateProjectKeyFlow")
    void shouldAuthenticateProjectKeyFlow() throws Exception {
        when(introspector.introspect("key-2"))
                .thenReturn(new IntrospectionResult("my-project", List.of("admin"), true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "key-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isInstanceOf(ApiKeyAuthenticationToken.class);
        assertThat(authentication.getName()).isEqualTo("my-project");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("admin");
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("shouldAuthenticateJwtRootedFlow")
    void shouldAuthenticateJwtRootedFlow() throws Exception {
        when(introspector.introspect("key-3"))
                .thenReturn(new IntrospectionResult("user-1", List.of("ConfigAdmin"), false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "key-3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getName()).isEqualTo("user-1");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("admin");
    }

    @Test
    @DisplayName("shouldAvoidSecondIntrospectorCallOnCacheHit")
    void shouldAvoidSecondIntrospectorCallOnCacheHit() throws Exception {
        when(introspector.introspect("key-4"))
                .thenReturn(new IntrospectionResult("my-project", List.of("admin"), true));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        firstRequest.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "key-4");
        filter.doFilter(firstRequest, new MockHttpServletResponse(), chain);

        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "key-4");
        filter.doFilter(secondRequest, new MockHttpServletResponse(), chain);

        verify(introspector, times(1)).introspect("key-4");
    }

    @Test
    @DisplayName("shouldReturn401OnBadCredentials")
    void shouldReturn401OnBadCredentials() throws Exception {
        when(introspector.introspect("bad-key")).thenThrow(new BadCredentialsException("Invalid API key"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "bad-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("shouldReturn503OnAuthenticationServiceException")
    void shouldReturn503OnAuthenticationServiceException() throws Exception {
        when(introspector.introspect("unreachable-core-key"))
                .thenThrow(new AuthenticationServiceException("Core down"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "unreachable-core-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getContentAsString()).contains("UPSTREAM_AUTH_ERROR");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("shouldMarkTokenUnauthenticatedWhenNoAuthoritiesResolved")
    void shouldMarkTokenUnauthenticatedWhenNoAuthoritiesResolved() throws Exception {
        when(introspector.introspect("key-5"))
                .thenReturn(new IntrospectionResult("my-project", List.of("unknown-role"), true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CoreApiKeyIntrospector.API_KEY_HEADER, "key-5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isFalse();
    }
}
