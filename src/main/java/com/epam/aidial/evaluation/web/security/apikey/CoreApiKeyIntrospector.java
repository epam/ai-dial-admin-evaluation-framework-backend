package com.epam.aidial.evaluation.web.security.apikey;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(value = "config.rest.security.api-key.enabled", havingValue = "true")
public class CoreApiKeyIntrospector {

    public static final String API_KEY_HEADER = "Api-Key";
    private static final String USER_INFO_PATH = "/v1/user/info";
    private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new ParameterizedTypeReference<>() {};

    @Qualifier("apiKeyIntrospectionRestClient")
    private final RestClient apiKeyIntrospectionRestClient;

    private final ApiKeyProperties properties;
    private final JwtSecurityProperties jwtSecurityProperties;

    @PostConstruct
    public void probeCore() {
        if (!properties.isStartupProbe()) {
            return;
        }
        try {
            apiKeyIntrospectionRestClient
                    .get()
                    .uri(USER_INFO_PATH)
                    .header(API_KEY_HEADER, "dial-eval-startup-probe")
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
        List<String> rawRoles = extractRoles(response.get("roles"));

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
            List<String> userClaimsRoles = extractRoles(userClaims.get(properties.getUserClaimsRoleClaim()));
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
                    .header(API_KEY_HEADER, apiKey)
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

    private List<String> extractRoles(Object rolesClaim) {
        if (rolesClaim instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        if (rolesClaim instanceof String s) {
            return List.of(s);
        }
        return List.of();
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
