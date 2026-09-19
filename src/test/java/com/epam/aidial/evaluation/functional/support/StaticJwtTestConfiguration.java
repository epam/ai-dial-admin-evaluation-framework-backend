package com.epam.aidial.evaluation.functional.support;

import com.epam.aidial.evaluation.web.security.TokenDecoderFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Provides a static {@code JwtDecoder} for MCP security functional tests, so no live identity
 * provider is needed. Registered under a bean name different from the production
 * {@code tokenDecoderFactory} bean (which stays in the context, unused) and marked {@code @Primary}:
 * {@code SecurityConfiguration#securityFilterChain} injects {@code TokenDecoderFactory} as a
 * method parameter resolved by type, so with two candidates {@code @Primary} deterministically
 * wins that by-type resolution, regardless of {@code @Import}/component-scan ordering. A same-named
 * {@code @Bean} override was tried first and did not win (verified empirically), because
 * {@code ConfigurationClassPostProcessor} expands this {@code @Import}-contributed class's
 * {@code @Bean} methods before it expands {@code SecurityConfiguration}'s in this project's
 * bootstrap, so the production bean's definition was registered last and overwrote it.
 */
@TestConfiguration
public class StaticJwtTestConfiguration {

    public static final String ALICE_TOKEN = "alice-token";

    /**
     * Carries a role ({@code guest}) outside {@code providers.test.allowedRoles}, so decoding
     * succeeds (valid issuer/audience/signature) but authorization rejects it — used to verify the
     * MCP endpoint enforces the same allowed-roles filtering as the REST API.
     */
    public static final String BOB_TOKEN = "bob-token";

    private static final String ALICE_SUBJECT = "alice";
    private static final String BOB_SUBJECT = "bob";
    private static final String ISSUER = "https://issuer.example.com";
    private static final String AUDIENCE = "test-audience";

    @Bean
    @Primary
    public TokenDecoderFactory staticTokenDecoderFactory() {
        return () -> StaticJwtTestConfiguration::decode;
    }

    private static Jwt decode(String token) {
        return switch (token) {
            case ALICE_TOKEN -> jwt(token, ALICE_SUBJECT, List.of("admin"));
            case BOB_TOKEN -> jwt(token, BOB_SUBJECT, List.of("guest"));
            default -> throw new BadJwtException("Invalid token: " + token);
        };
    }

    private static Jwt jwt(String token, String subject, List<String> roles) {
        Instant issuedAt = Instant.now();
        Map<String, Object> headers = Map.of("alg", "none");
        Map<String, Object> claims = Map.of("iss", ISSUER, "sub", subject, "aud", List.of(AUDIENCE), "roles", roles);
        return new Jwt(token, issuedAt, issuedAt.plusSeconds(3600), headers, claims);
    }
}
