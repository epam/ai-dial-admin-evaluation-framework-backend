package com.epam.aidial.evaluation.service.infrastructure.partition;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsPartitioningProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point for analytics partition maintenance: creates upcoming monthly partitions
 * ahead of need, then (only when retention is enabled) removes expired ones, then reports default
 * partition usage. Modeled on {@code TestCaseRunInputsRetentionJob}'s shape.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class AnalyticsPartitionMaintenanceJob {

    private final AnalyticsPartitionMaintenanceService maintenanceService;
    private final AnalyticsPartitioningProperties properties;

    @Scheduled(
            fixedDelayString = "${analytics.partitioning.maintenance-interval-ms}",
            initialDelayString = "${analytics.partitioning.initial-delay-ms}")
    public void maintainPartitions() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            log.debug("Analytics partition maintenance is disabled; skipping");
            return;
        }

        log.info("Starting analytics partition maintenance");
        try {
            maintenanceService.ensureFuturePartitions();
        } catch (Exception e) {
            log.warn("Analytics partition creation failed: {}", e.getMessage(), e);
        }
        try {
            maintenanceService.dropExpiredPartitions();
        } catch (Exception e) {
            log.warn("Analytics partition retention cleanup failed: {}", e.getMessage(), e);
        }
        try {
            maintenanceService.reportDefaultPartitionUsage();
        } catch (Exception e) {
            log.warn("Analytics default partition usage report failed: {}", e.getMessage(), e);
        }
    }
}
