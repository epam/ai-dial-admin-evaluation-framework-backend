package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs metric provider sync after startup (async, does not block) and on a schedule when enabled.
 * Only runs when sync.enabled is true and providers map is non-empty.
 * One provider failure is logged and does not stop the job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class MetricProviderSyncJob {

    private final MetricProviderProperties metricProviderProperties;
    private final MetricProviderSyncService metricProviderSyncService;

    /**
     * First run after startup; does not block startup (async).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        log.info("Triggering metric provider sync (application startup)");
        run();
    }

    /**
     * Recurring sync when metric-providers.sync.cron is set to a valid expression (use "-" to disable).
     */
    @Scheduled(cron = "${metric-providers.sync.cron}")
    public void runScheduledSync() {
        log.info("Triggering metric provider sync (scheduled run)");
        run();
    }

    private void run() {
        if (!metricProviderProperties.getSync().isEnabled()) {
            log.debug("Metric provider sync skipped: sync disabled");
            return;
        }
        Map<String, MetricProviderProperties.ProviderEntry> providers = metricProviderProperties.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.debug("Metric provider sync skipped: no providers configured");
            return;
        }
        log.info("Starting metric provider sync for {} provider(s)", providers.size());
        for (var entry : providers.entrySet()) {
            var providerId = entry.getKey();
            try {
                metricProviderSyncService.syncOne(providerId);
                log.debug("Metric provider sync completed for provider {}", providerId);
            } catch (Exception e) {
                log.warn("Metric provider sync failed for provider {}: {}", providerId, e.getMessage(), e);
            }
        }
        log.info("Metric provider sync finished");
    }
}
