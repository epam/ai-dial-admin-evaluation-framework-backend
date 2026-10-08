package com.epam.aidial.evaluation.web.security.apikey;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties;
import com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.web.security.ClaimPathExtractor;
import com.epam.aidial.evaluation.web.security.JwtProviderUtils;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Deliberately NOT annotated with {@code @LogExecution}: its public methods take the plaintext API
 * key, and the opt-in trace advisor renders method arguments verbatim, which would write the secret
 * to the log. Guarded by {@code SecretHandlingLoggingTest}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "config.rest.security.api-key.enabled", havingValue = "true")
public class CoreApiKeyIntrospector {

    private static final String USER_INFO_PATH = "/v1/user/info";
    private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new ParameterizedTypeReference<>() {};

    @Qualifier("apiKeyIntrospectionRestClient")
    private final RestClient apiKeyIntrospectionRestClient;

    private final ApiKeyProperties properties;
    private final JwtSecurityProperties jwtSecurityProperties;

    /**
     * Present only when {@code config.rest.security.mode=oidc} ({@link JwtProvidersProperties} is
     * conditional on that property and can be independently absent while API-key auth is enabled via
     * {@code config.rest.security.api-key.enabled}, e.g. a {@code mode=none} deployment). When absent,
     * or when no configured provider's accepted-issuer set matches the token's {@code iss}, role
     * resolution falls back to the flat {@link ApiKeyProperties#getUserClaimsRoleClaim()}.
     */
    private final Optional<JwtProvidersProperties> jwtProvidersProperties;

    private final JwtProviderUtils jwtProviderUtils;

    @PostConstruct
    public void probeCore() {
        if (!properties.isStartupProbe()) {
            return;
        }
        try {
            apiKeyIntrospectionRestClient
                    .get()
                    .uri(USER_INFO_PATH)
                    .header(CallerCredential.API_KEY_HEADER, "dial-eval-startup-probe")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(STRING_OBJECT_MAP);
            log.info("DIAL Core {} reachable at {}", USER_INFO_PATH, properties.getCoreUrl());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.info(
                        "DIAL Core {} reachable at {} (responded {})",
                        USER_INFO_PATH,
                        properties.getCoreUrl(),
                        e.getStatusCode());
            } else {
                throw new IllegalStateException(
                        "DIAL Core " + USER_INFO_PATH + " responded with " + e.getStatusCode() + " at "
                                + properties.getCoreUrl()
                                + ". Disable the startup probe via config.rest.security.api-key.startup-probe=false to skip this check.",
                        e);
            }
        } catch (ResourceAccessException e) {
            throw new IllegalStateException(
                    "DIAL Core " + USER_INFO_PATH + " is unreachable at " + properties.getCoreUrl()
                            + ". Disable the startup probe via config.rest.security.api-key.startup-probe=false to skip this check.",
                    e);
        }
    }

    public IntrospectionResult introspect(String apiKey) {
        Map<String, Object> response = callCore(apiKey);
        List<String> rawRoles = ClaimPathExtractor.extractRoleValues(response.get("roles"));

        if (response.get("project") instanceof String project && StringUtils.isNotBlank(project)) {
            return new IntrospectionResult(project, rawRoles, true);
        }

        if (response.get("userClaims") instanceof Map<?, ?> userClaimsRaw && !userClaimsRaw.isEmpty()) {
            Map<String, List<String>> userClaims = normalizeUserClaims(userClaimsRaw);
            String principal = firstNonBlank(userClaims.get(jwtSecurityProperties.getUserClaim()));
            if (StringUtils.isBlank(principal)) {
                log.warn(
                        "Core {} userClaims response is missing the configured principal claim '{}'",
                        USER_INFO_PATH,
                        jwtSecurityProperties.getUserClaim());
                throw new BadCredentialsException("Malformed Core user-info response");
            }
            List<String> userClaimsRoles = resolveUserClaimsRoles(userClaimsRaw, userClaims);
            return new IntrospectionResult(principal, userClaimsRoles, false);
        }

        log.warn("Core {} response contains neither 'project' nor 'userClaims'", USER_INFO_PATH);
        throw new BadCredentialsException("Malformed Core user-info response");
    }

    private Map<String, Object> callCore(String apiKey) {
        Map<String, Object> body;
        try {
            body = apiKeyIntrospectionRestClient
                    .get()
                    .uri(USER_INFO_PATH)
                    .header(CallerCredential.API_KEY_HEADER, apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(STRING_OBJECT_MAP);
        } catch (RestClientResponseException e) {
            log.debug("Core {} rejected API key with status {}", USER_INFO_PATH, e.getStatusCode(), e);
            throw new BadCredentialsException("Invalid API key");
        } catch (ResourceAccessException e) {
            log.warn("Failed to reach Core {} at {}", USER_INFO_PATH, properties.getCoreUrl(), e);
            throw new AuthenticationServiceException("Failed to validate API key with DIAL Core", e);
        }
        if (body == null) {
            log.debug("Core {} responded with an empty body", USER_INFO_PATH);
            throw new BadCredentialsException("Invalid API key");
        }
        return body;
    }

    /**
     * Resolves JWT-rooted roles for Core's {@code userClaims} map: when a configured identity provider
     * matches the token's {@code iss}, uses that provider's {@code roleClaims} (same resolution as
     * bearer-JWT authorities, see {@link com.epam.aidial.evaluation.web.security.JwtAuthenticationConverterFactory});
     * otherwise falls back to the flat {@link ApiKeyProperties#getUserClaimsRoleClaim()}. Operates on the
     * raw, pre-flattening {@code userClaimsRaw} map (not {@code userClaims}) so a dot-separated nested
     * {@code roleClaims} path can traverse the original claim structure that {@link #normalizeUserClaims}
     * would otherwise flatten away.
     */
    private List<String> resolveUserClaimsRoles(Map<?, ?> userClaimsRaw, Map<String, List<String>> userClaims) {
        Optional<JwtProvidersProperties.ProviderConfig> provider = resolveProvider(userClaimsRaw);
        if (provider.isPresent()) {
            Set<String> roles = ClaimPathExtractor.extractRoles(provider.get().getRoleClaims(), userClaimsRaw);
            if (!roles.isEmpty()) {
                return List.copyOf(roles);
            }
            log.warn(
                    "Core {} userClaims matched a configured provider but its roleClaims {} yielded no roles; "
                            + "falling back to '{}'",
                    USER_INFO_PATH,
                    provider.get().getRoleClaims(),
                    properties.getUserClaimsRoleClaim());
        }
        return ClaimPathExtractor.extractRoleValues(userClaims.get(properties.getUserClaimsRoleClaim()));
    }

    private Optional<JwtProvidersProperties.ProviderConfig> resolveProvider(Map<?, ?> userClaimsRaw) {
        if (jwtProvidersProperties.isEmpty()) {
            log.debug(
                    "JwtProvidersProperties bean absent (config.rest.security.mode=none); using flat role claim '{}'",
                    properties.getUserClaimsRoleClaim());
            return Optional.empty();
        }
        // Core's userClaims response wraps every claim value, including 'iss', as a JSON array
        // (single-element for a scalar OIDC claim) rather than a bare string, so 'iss' must be
        // normalized the same way as any other userClaims value before comparison.
        String issuer = firstNonBlank(ClaimPathExtractor.extractRoleValues(userClaimsRaw.get("iss")));
        if (StringUtils.isBlank(issuer)) {
            log.debug(
                    "Core {} userClaims is missing a non-blank 'iss'; using flat role claim '{}'",
                    USER_INFO_PATH,
                    properties.getUserClaimsRoleClaim());
            return Optional.empty();
        }
        for (JwtProvidersProperties.ProviderConfig config :
                jwtProvidersProperties.get().getProviders().values()) {
            if (jwtProviderUtils.getAcceptedIssuers(config).contains(issuer)) {
                return Optional.of(config);
            }
        }
        log.debug(
                "No configured provider accepts issuer '{}'; using flat role claim '{}'",
                issuer,
                properties.getUserClaimsRoleClaim());
        return Optional.empty();
    }

    private static Map<String, List<String>> normalizeUserClaims(Map<?, ?> raw) {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                result.put(
                        name,
                        list.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList());
            } else if (value instanceof String s) {
                result.put(name, List.of(s));
            }
        }
        return result;
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(StringUtils::isNotBlank).findFirst().orElse(null);
    }
}
