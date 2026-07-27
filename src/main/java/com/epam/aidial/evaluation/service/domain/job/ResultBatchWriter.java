package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Thread-safe result buffer that flushes to analytics DB at batch size threshold.
 * Replaces MockResultsBatchWriter with real batch writing + progress reporting.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ResultBatchWriter {

    private final ResultBatchWriterTransactional transactionalWriter;
    private final TestSuiteRunSseService sseService;

    /**
     * Per-run buffer state. Created for each evaluation run.
     */
    public static class RunBuffer {
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

        public RunBuffer(int batchSize, UUID runId, UUID suiteId, int totalCases) {
            this.batchSize = batchSize;
            this.runId = runId;
            this.suiteId = suiteId;
            this.totalCases = totalCases;
        }

        public int getTotalFlushed() {
            return totalFlushed.get();
        }
    }

    public RunBuffer createBuffer(int batchSize, UUID runId, UUID suiteId, int totalCases) {
        return new RunBuffer(batchSize, runId, suiteId, totalCases);
    }

    /**
     * Adds a single-test-case-run result to the buffer (single-turn case, or a synthetic ERROR row).
     * Counts one progress unit. Thread-safe via ReentrantLock.
     */
    public void addResult(RunBuffer buffer, TestCaseRunResult result) {
        addResults(buffer, List.of(result));
    }

    /**
     * Adds all result rows of one test-case run to the buffer (one row for single-turn; N rows for a
     * multi-turn case). Flushes when batch size is reached and advances run progress by exactly <b>one</b>
     * unit regardless of how many rows the test-case run wrote. Thread-safe via ReentrantLock.
     */
    public void addResults(RunBuffer buffer, List<TestCaseRunResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        List<TestCaseRunResult> toFlush = null;
        buffer.lock.lock();
        try {
            buffer.buffer.addAll(results);
            if (buffer.buffer.size() >= buffer.batchSize) {
                toFlush = new ArrayList<>(buffer.buffer);
                buffer.buffer.clear();
            }
        } finally {
            buffer.lock.unlock();
        }

        // One progress unit per test-case run, regardless of how many rows it wrote. Counted here; the SSE
        // notification is emitted on flush (below) carrying this cumulative test-case count.
        buffer.testCasesCompleted.incrementAndGet();
        if (toFlush != null) {
            doFlush(buffer, toFlush);
        }
    }

    /**
     * Flushes all remaining buffered results. Called on completion or cancellation.
     */
    public void flush(RunBuffer buffer) {
        List<TestCaseRunResult> toFlush;
        buffer.lock.lock();
        try {
            if (buffer.buffer.isEmpty()) {
                return;
            }
            toFlush = new ArrayList<>(buffer.buffer);
            buffer.buffer.clear();
        } finally {
            buffer.lock.unlock();
        }

        doFlush(buffer, toFlush);
    }

    private void doFlush(RunBuffer buffer, List<TestCaseRunResult> batch) {
        transactionalWriter.saveBatch(batch);
        int flushed = buffer.totalFlushed.addAndGet(batch.size());
        log.debug("Flushed {} result rows for run {} (total: {})", batch.size(), buffer.runId, flushed);

        // Progress is reported in test-case-run units (one per completed test-case run), not rows — so a
        // multi-turn case that wrote N rows still advances the bar by one. For single-turn runs this equals
        // the number of results flushed, preserving the previous behavior.
        try {
            sseService.notifyProgress(buffer.runId, buffer.suiteId, buffer.testCasesCompleted.get(), buffer.totalCases);
        } catch (Exception e) {
            log.debug("Failed to send progress SSE for run {}: {}", buffer.runId, e.getMessage(), e);
        }
    }
}
