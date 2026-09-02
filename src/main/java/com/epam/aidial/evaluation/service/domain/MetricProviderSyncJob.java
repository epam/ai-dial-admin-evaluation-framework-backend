package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
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
 * Within a run, a provider entry whose own enabled flag is false is logged and skipped.
 * One provider failure is logged and does not stop the job.
 *
 * <p>Concurrency: this job runs on every application instance without leader election, so two instances
 * may sync the same provider at once. Version assignment is not locked (see
 * {@code PostgresMetricDeclarationVersionRepository.save}); the loser of the race fails on
 * uq_metric_declaration_versions_declaration_version, its provider transaction rolls back whole, and the
 * next scheduled run re-syncs it. That 23505 is therefore expected traffic and is logged at info without
 * a stacktrace and without an immediate retry (a retry would only add provider HTTP load). Everything
 * else, deadlocks (40P01) included, stays a warn with the throwable - metrics are iterated in a
 * deterministic order, so a lock cycle should be impossible and one occurring is a real anomaly.
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
        final Map<String, MetricProviderProperties.ProviderEntry> providers = metricProviderProperties.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.debug("Metric provider sync skipped: no providers configured");
            return;
        }
        log.info("Starting metric provider sync for {} provider(s)", providers.size());
        for (var entry : providers.entrySet()) {
            final var providerId = entry.getKey();
            final var provider = entry.getValue();

            if (!provider.getEnabled()) {
                log.info("Provider {} is disabled, skipping", providerId);
                continue;
            }

            try {
                metricProviderSyncService.syncOne(providerId);
                log.debug("Metric provider sync completed for provider {}", providerId);
            } catch (Exception e) {
                if (UniqueConstraintViolationDetector.isUniqueViolation(e)) {
                    log.info(
                            "Metric provider sync for provider {} lost a concurrent version-assignment race; "
                                    + "its transaction rolled back and the next scheduled run will re-sync",
                            providerId);
                } else {
                    log.warn("Metric provider sync failed for provider {}: {}", providerId, e.getMessage(), e);
                }
            }
        }
        log.info("Metric provider sync finished");
    }
}
