package com.epam.aidial.evaluation.configuration.security;

import com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties;
import com.epam.aidial.evaluation.web.security.IssuerToDecoderMapFactory;
import com.epam.aidial.evaluation.web.security.JwtAuthenticationConverterFactory;
import com.epam.aidial.evaluation.web.security.JwtProviderUtils;
import com.epam.aidial.evaluation.web.security.TokenDecoderFactory;
import com.epam.aidial.evaluation.web.security.TokenDecoderFactoryImpl;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(value = "config.rest.security.mode", havingValue = "oidc", matchIfMissing = true)
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final JwtProvidersProperties jwtProviderProperties;
    private final JwtProviderUtils jwtProviderUtils;

    @Value("${config.rest.security.default.allowedRoles}")
    protected Set<String> defaultAllowedRoles;

    @Value("${config.rest.security.disable-swagger-authorization}")
    protected boolean disableSwaggerAuthorization;

    @Bean
    public Map<String, Set<String>> allowedRolesByIssuer() {
        Map<String, Set<String>> tmpRolesByIssuer = new HashMap<>();
        var providers = jwtProviderProperties.getProviders();
        providers.forEach((name, config) -> {
            Set<String> acceptedRoles = new HashSet<>(defaultAllowedRoles);
            if (config.getAllowedRoles() != null) {
                acceptedRoles.addAll(config.getAllowedRoles());
            }
            var acceptedIssuers = jwtProviderUtils.getAcceptedIssuers(config);
            for (var issuer : acceptedIssuers) {
                tmpRolesByIssuer.put(issuer, acceptedRoles);
            }
        });
        return Map.copyOf(tmpRolesByIssuer);
    }

    @Bean
    public JwtAuthenticationConverterFactory jwtAuthenticationConverterFactory() {
        return new JwtAuthenticationConverterFactory(jwtProviderProperties.getProviders(), jwtProviderUtils);
    }

    @Bean
    public IssuerToDecoderMapFactory issuerToDecoderMapFactory() {
        return new IssuerToDecoderMapFactory(jwtProviderUtils);
    }

    @Bean
    public TokenDecoderFactory tokenDecoderFactory(IssuerToDecoderMapFactory issuerToDecoderMapFactory) {
        return new TokenDecoderFactoryImpl(jwtProviderProperties.getProviders(), issuerToDecoderMapFactory);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenDecoderFactory tokenDecoderFactory,
            JwtAuthenticationConverterFactory jwtAuthenticationConverterFactory,
            Map<String, Set<String>> allowedRolesByIssuer)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(publicPathPatterns())
                        .permitAll()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2ResourceServer ->
                        oauth2ResourceServer.jwt(jwt -> jwt.decoder(tokenDecoderFactory.createJwtDecoder())
                                .jwtAuthenticationConverter(token -> {
                                    var issuer = token.getIssuer().toString();
                                    var converter = jwtAuthenticationConverterFactory.getConverter(issuer);
                                    var authenticationToken = converter.convert(token);
                                    var allowedRolesForIssuer = allowedRolesByIssuer.getOrDefault(issuer, Set.of());
                                    var filtered = authenticationToken.getAuthorities().stream()
                                            .map(GrantedAuthority::getAuthority)
                                            .filter(allowedRolesForIssuer::contains)
                                            .map(SimpleGrantedAuthority::new)
                                            .toList();
                                    log.trace(
                                            "Authorization state - token: {}, issuer: {}, authenticationToken: {}, "
                                                    + "allowedRolesForIssuer: {}, authorities: {}",
                                            token,
                                            issuer,
                                            authenticationToken,
                                            allowedRolesForIssuer,
                                            authenticationToken.getAuthorities());
                                    if (filtered.isEmpty()) {
                                        log.warn(
                                                "Access denied for issuer: {}. No allowed roles for user: {}",
                                                issuer,
                                                authenticationToken.getName());
                                        return new JwtAuthenticationToken(token);
                                    }
                                    return new JwtAuthenticationToken(token, filtered, authenticationToken.getName());
                                })));
        return http.build();
    }

    protected String[] publicPathPatterns() {
        var swaggerPaths =
                disableSwaggerAuthorization ? List.of("/swagger-ui/**", "/v3/api-docs/**") : List.<String>of();
        var unsecuredPaths = List.of("/api/v1/health/**", "/api/internal/**");

        return Stream.of(swaggerPaths, unsecuredPaths)
                .flatMap(Collection::stream)
                .toArray(String[]::new);
    }
}
