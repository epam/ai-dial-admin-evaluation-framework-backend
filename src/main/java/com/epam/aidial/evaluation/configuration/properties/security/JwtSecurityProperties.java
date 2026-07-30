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
}
