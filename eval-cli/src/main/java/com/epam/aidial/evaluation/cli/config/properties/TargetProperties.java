package com.epam.aidial.evaluation.cli.config.properties;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The target DIAL Core instance's API key, sent as the {@code Api-Key} header on every request to the
 * target deployment.
 *
 * <p>Bound under the same {@code dial.components.core} prefix as {@code evaluation-runner-core}'s own
 * {@code DialCoreProperties} (host/timeouts) — Spring allows multiple {@code @ConfigurationProperties}
 * classes on the same prefix as long as their field sets don't overlap. Sourced from an env var only
 * ({@code DIAL_CORE_API_KEY}); there is deliberately no file-based or CLI-flag delivery for it — in a
 * CI job (this module's primary deployment context), the platform's own secret store → env var
 * injection already gets automatic log redaction and leaves no on-disk artifact to clean up, so a
 * plain env var is the more idiomatic and no less safe choice here.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "dial.components.core")
public class TargetProperties {

    @NotBlank
    private String apiKey;
}
