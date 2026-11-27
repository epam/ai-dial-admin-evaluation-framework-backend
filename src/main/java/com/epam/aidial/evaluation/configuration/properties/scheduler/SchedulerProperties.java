package com.epam.aidial.evaluation.configuration.properties.scheduler;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for scheduled test suite runs delegated to the DIAL Core scheduler application.
 * Defaults live in {@code application.yml}; this class holds structure and validation only.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "dial.scheduler")
public class SchedulerProperties {

    private boolean enabled;

    @NotBlank
    private String appId;

    private String callbackBaseUrl;

    @NotBlank
    private String serviceId;

    @Min(1)
    private int timeoutSeconds;

    @AssertTrue(message = "dial.scheduler.callback-base-url must be set when dial.scheduler.enabled=true")
    public boolean isCallbackBaseUrlPresentWhenEnabled() {
        return !enabled || (callbackBaseUrl != null && !callbackBaseUrl.isBlank());
    }
}
