package com.epam.aidial.evaluation.cli.config.properties;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the source EF instance HTTP client.
 *
 * <p>Manually kept in sync with the deployment environment: all required fields must be provided
 * via environment variables or application.yml overrides.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "eval.source")
public class SourceProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String token;

    @Min(0)
    private int connectTimeoutMs;

    @Min(0)
    private int readTimeoutMs;
}
