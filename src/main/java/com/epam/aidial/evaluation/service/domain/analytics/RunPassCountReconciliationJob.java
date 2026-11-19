package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.properties.analytics.RunPassCountReconciliationProperties;
import com.epam.aidial.evaluation.data.db.model.CompletedRunKey;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records the pass counts that are missing for {@code COMPLETED} runs: records whose write failed, runs
 * completed before records existed, and completed runs that received a newer computation through the
 * eval-summary write API. A pass pages through COMPLETED runs in meta by keyset, then hands each page to
 * {@link RunPassCountService#reconcileBatch}, which works in one analytics transaction; the meta read never runs
 * inside it.
 *
 * <p>Runs on every instance without a lock: the insert is idempotent, so concurrent passes are harmless. A failing
 * batch is logged and the pass continues with the next one.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class RunPassCountReconciliationJob {

    private final RunPassCountReconciliationProperties properties;
    private final TestSuiteRunService testSuiteRunService;
    private final RunPassCountService runPassCountService;

    @Scheduled(
            initialDelayString = "${analytics.run-pass-counts.reconciliation.initial-delay-ms}",
            fixedDelayString = "${analytics.run-pass-counts.reconciliation.interval-ms}")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * Runs one full pass; a no-op when disabled.
     *
     * @return the number of pass-count rows written
     */
    public int reconcile() {
        if (!properties.isEnabled()) {
            log.debug("Run pass count reconciliation skipped: disabled");
            return 0;
        }
        final int batchSize = properties.getBatchSize();
        int written = 0;
        Long afterCreatedAtMs = null;
        UUID afterId = null;
        try {
            while (true) {
                final List<CompletedRunKey> page =
                        testSuiteRunService.getCompletedRunKeysAfter(afterCreatedAtMs, afterId, batchSize);
                if (page.isEmpty()) {
                    break;
                }
                written += reconcileBatchQuietly(page);
                final CompletedRunKey last = page.getLast();
                afterCreatedAtMs = last.createdAt();
                afterId = last.id();
                if (page.size() < batchSize) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // Broad on purpose: a meta read failure of any kind (DataAccessException, a TransactionException from
            // connection acquisition) ends this pass only; the next scheduled pass resumes from the start.
            log.warn("Run pass count reconciliation aborted after {} rows: {}", written, e.getMessage(), e);
        }
        log.info("Run pass count reconciliation wrote {} rows", written);
        return written;
    }

    private int reconcileBatchQuietly(List<CompletedRunKey> page) {
        try {
            return runPassCountService.reconcileBatch(page, properties.getMinComputationAgeMs());
        } catch (RuntimeException e) {
            // Broad on purpose, as in reconcile(): one failing batch must not stop the rest of the pass.
            log.warn("Run pass count reconciliation batch of {} runs failed: {}", page.size(), e.getMessage(), e);
            return 0;
        }
    }
}
