package com.epam.aidial.evaluation.configuration.properties.security;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public class JwtSecurityProperties {

    @NotBlank
    private String userClaim;

    /**
     * When {@code true}, {@code AuthorResolver} calls DIAL Core's user-info endpoint and uses
     * {@code userDisplayName} for {@code created_by}, falling back to the raw claim value when the
     * name is absent or Core is unavailable.
     */
    private boolean resolveUserName;
}
