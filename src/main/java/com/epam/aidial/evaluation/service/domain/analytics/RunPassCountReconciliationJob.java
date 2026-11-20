package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.properties.analytics.RunPassCountReconciliationProperties;
import com.epam.aidial.evaluation.data.db.model.CompletedRunKey;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import java.time.Clock;
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
    private final Clock clock;

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
        final long startedAtMs = clock.millis();
        log.info(
                "Run pass count reconciliation started (batch size {}, min computation age {} ms)",
                batchSize,
                properties.getMinComputationAgeMs());

        final PassTotals totals = new PassTotals();
        Long afterCreatedAtMs = null;
        UUID afterId = null;
        try {
            // Keyset paging: keep going while pages come back full; a short (or empty) page is the last one.
            List<CompletedRunKey> page;
            do {
                page = testSuiteRunService.getCompletedRunKeysAfter(afterCreatedAtMs, afterId, batchSize);
                if (!page.isEmpty()) {
                    reconcileBatchQuietly(page, totals);
                    final CompletedRunKey last = page.getLast();
                    afterCreatedAtMs = last.createdAt();
                    afterId = last.id();
                }
            } while (page.size() == batchSize);
        } catch (RuntimeException e) {
            // Broad on purpose: a meta read failure of any kind (DataAccessException, a TransactionException from
            // connection acquisition) ends this pass only; the next scheduled pass resumes from the start.
            log.warn(
                    "Run pass count reconciliation aborted after {} ms: {} ({}): {}",
                    clock.millis() - startedAtMs,
                    totals.describe(),
                    position(afterCreatedAtMs, afterId),
                    e.getMessage(),
                    e);
            return totals.written;
        }
        log.info(
                "Run pass count reconciliation finished in {} ms: {}", clock.millis() - startedAtMs, totals.describe());
        return totals.written;
    }

    private void reconcileBatchQuietly(List<CompletedRunKey> page, PassTotals totals) {
        totals.batches++;
        totals.scanned += page.size();
        try {
            final ReconciledBatch batch = runPassCountService.reconcileBatch(page, properties.getMinComputationAgeMs());
            totals.written += batch.written();
            totals.deferred += batch.deferred();
        } catch (RuntimeException e) {
            // Broad on purpose, as in reconcile(): one failing batch must not stop the rest of the pass.
            totals.failedBatches++;
            final CompletedRunKey first = page.getFirst();
            final CompletedRunKey last = page.getLast();
            log.warn(
                    "Run pass count reconciliation batch of {} runs failed (first run {} created at {}, last run {}"
                            + " created at {}): {}",
                    page.size(),
                    first.id(),
                    first.createdAt(),
                    last.id(),
                    last.createdAt(),
                    e.getMessage(),
                    e);
        }
    }

    private static String position(Long afterCreatedAtMs, UUID afterId) {
        return afterId == null
                ? "failed reading the first page"
                : "failed reading the page after run " + afterId + " created at " + afterCreatedAtMs;
    }

    /** Counters of one pass, for its log lines. */
    private static final class PassTotals {
        private int scanned;
        private int batches;
        private int failedBatches;
        private int written;
        private int deferred;

        private String describe() {
            return scanned + " runs scanned in " + batches + " batches (" + failedBatches + " failed), " + written
                    + " rows written, " + deferred + " runs deferred as too recent";
        }
    }
}
