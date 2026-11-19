package com.epam.aidial.evaluation.configuration.properties.analytics;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the job that records missing run pass counts. Defaults live in {@code application.yml} only.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "analytics.run-pass-counts.reconciliation")
public class RunPassCountReconciliationProperties {

    private boolean enabled;

    @Min(0)
    private long initialDelayMs;

    @Min(1)
    private long intervalMs;

    @Min(1)
    private int batchSize;

    @Min(0)
    private long minComputationAgeMs;
}
