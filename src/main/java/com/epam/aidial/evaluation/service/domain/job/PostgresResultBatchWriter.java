package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.runner.job.ResultBatchWriter;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres-backed {@link ResultBatchWriter}: buffers results, flushes to the analytics DB at batch-size
 * threshold, and reports progress via SSE. One instance per run — created by
 * {@link PostgresResultBatchWriterFactory}, not itself a Spring bean, so its transactional flush is
 * demarcated explicitly via {@link TransactionTemplate} rather than {@code @Transactional} (which only
 * works on Spring-proxied beans).
 */
@Slf4j
class PostgresResultBatchWriter implements ResultBatchWriter {

    private final TestCaseRunResultRepository resultRepository;
    private final TestSuiteRunSseService sseService;
    private final TransactionTemplate analyticsTransactionTemplate;

    private final List<TestCaseRunResult> buffer = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger totalFlushed = new AtomicInteger(0);
    // Progress is counted per test-case run (one unit per completed test-case run), not per persisted
    // row — a multi-turn case writes N rows but advances progress by exactly one unit.
    private final AtomicInteger testCasesCompleted = new AtomicInteger(0);
    private final int batchSize;
    private final UUID runId;
    private final UUID suiteId;
    private final int totalCases;

    PostgresResultBatchWriter(
            TestCaseRunResultRepository resultRepository,
            TestSuiteRunSseService sseService,
            TransactionTemplate analyticsTransactionTemplate,
            int batchSize,
            UUID runId,
            UUID suiteId,
            int totalCases) {
        this.resultRepository = resultRepository;
        this.sseService = sseService;
        this.analyticsTransactionTemplate = analyticsTransactionTemplate;
        this.batchSize = batchSize;
        this.runId = runId;
        this.suiteId = suiteId;
        this.totalCases = totalCases;
    }

    /**
     * Adds all result rows of one test-case run to the buffer (one row for single-turn; N rows for a
     * multi-turn case). Flushes when batch size is reached and advances run progress by exactly <b>one</b>
     * unit regardless of how many rows the test-case run wrote. Thread-safe via ReentrantLock.
     */
    @Override
    public void addResults(List<TestCaseRunResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        List<TestCaseRunResult> toFlush = null;
        lock.lock();
        try {
            buffer.addAll(results);
            if (buffer.size() >= batchSize) {
                toFlush = new ArrayList<>(buffer);
                buffer.clear();
            }
        } finally {
            lock.unlock();
        }

        // One progress unit per test-case run, regardless of how many rows it wrote. Counted here; the SSE
        // notification is emitted on flush (below) carrying this cumulative test-case count.
        testCasesCompleted.incrementAndGet();
        if (toFlush != null) {
            doFlush(toFlush);
        }
    }

    /**
     * Flushes all remaining buffered results. Called on completion or cancellation.
     */
    @Override
    public void flush() {
        List<TestCaseRunResult> toFlush;
        lock.lock();
        try {
            if (buffer.isEmpty()) {
                return;
            }
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        } finally {
            lock.unlock();
        }

        doFlush(toFlush);
    }

    int getTotalFlushed() {
        return totalFlushed.get();
    }

    private void doFlush(List<TestCaseRunResult> batch) {
        analyticsTransactionTemplate.executeWithoutResult(status -> resultRepository.saveAll(batch));
        int flushed = totalFlushed.addAndGet(batch.size());
        log.debug("Flushed {} result rows for run {} (total: {})", batch.size(), runId, flushed);

        // Progress is reported in test-case-run units (one per completed test-case run), not rows — so a
        // multi-turn case that wrote N rows still advances the bar by one. For single-turn runs this equals
        // the number of results flushed, preserving the previous behavior.
        try {
            sseService.notifyProgress(runId, suiteId, testCasesCompleted.get(), totalCases);
        } catch (Exception e) {
            log.debug("Failed to send progress SSE for run {}: {}", runId, e.getMessage(), e);
        }
    }
}
