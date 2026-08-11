package com.epam.aidial.evaluation.configuration.properties.security;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Getter
@Setter
@Slf4j
@Component
@Validated
@LogExecution
@ConfigurationProperties(prefix = "config.rest.security.api-key")
@ConditionalOnProperty(value = "config.rest.security.api-key.enabled", havingValue = "true")
public class ApiKeyProperties {

    private final ObjectMapper objectMapper;

    private boolean enabled;
    private String coreUrl;
    private int cacheTtlSeconds;
    private int cacheMaxSize;
    private int requestTimeoutMs;
    private String rolesMapping;
    private String defaultRolesMapping;
    private String userClaimsRoleClaim;
    private boolean startupProbe;

    private Map<String, List<String>> parsedRolesMapping = Collections.emptyMap();
    private Map<String, List<String>> parsedDefaultRolesMapping = Collections.emptyMap();

    public ApiKeyProperties(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void validate() {
        if (!enabled) {
            log.debug("DIAL API-Key authentication is disabled");
            return;
        }

        if (StringUtils.isBlank(coreUrl)) {
            throw new IllegalStateException(
                    "config.rest.security.api-key.enabled=true requires config.rest.security.api-key.core-url to be set");
        }

        parsedRolesMapping = parseRolesMapping(rolesMapping, "config.rest.security.api-key.roles-mapping");
        parsedDefaultRolesMapping =
                parseRolesMapping(defaultRolesMapping, "config.rest.security.api-key.default-roles-mapping");
        if (parsedRolesMapping.isEmpty() && parsedDefaultRolesMapping.isEmpty()) {
            throw new IllegalStateException("config.rest.security.api-key.enabled=true requires at least one of "
                    + "config.rest.security.api-key.roles-mapping (used for project-key callers) or "
                    + "config.rest.security.api-key.default-roles-mapping (used for JWT-rooted per-request keys) "
                    + "to map at least one role to an authority. Otherwise every authenticated API-key caller "
                    + "would be rejected.");
        }

        log.info(
                "DIAL API-Key authentication is enabled. Core URL: {}, cache TTL: {}s, "
                        + "project-key mapped roles: {}, JWT-rooted mapped roles: {}",
                coreUrl,
                cacheTtlSeconds,
                parsedRolesMapping.keySet(),
                parsedDefaultRolesMapping.keySet());
    }

    private Map<String, List<String>> parseRolesMapping(String json, String propertyName) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, List<String>>>() {});
        } catch (JacksonException e) {
            throw new IllegalStateException("Invalid " + propertyName + " JSON: " + e.getMessage(), e);
        }
    }
}
