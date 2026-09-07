package com.epam.aidial.evaluation.configuration.properties.analytics;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Controls for {@code AnalyticsPartitionMaintenanceJob}: how far ahead monthly partitions are
 * created, whether/when they are removed, and the removal mode.
 *
 * <p>Defaults live in {@code application.yml} only — never as a Java field initializer.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "analytics.partitioning")
public class AnalyticsPartitioningProperties {

    @NotNull
    private Boolean enabled;

    @NotNull
    @Min(1)
    private Integer lookAheadMonths;

    /** 0 means retention is disabled — no partition is ever dropped or detached. */
    @NotNull
    @Min(0)
    private Integer retentionMonths;

    @NotNull
    private Boolean archiveInsteadOfDrop;

    @NotNull
    @Min(1)
    private Long maintenanceIntervalMs;

    @NotNull
    @Min(0)
    private Long initialDelayMs;
}
