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
     * Adds a result to the buffer. Flushes when batch size is reached.
     * Thread-safe via ReentrantLock.
     */
    public void addResult(RunBuffer buffer, TestCaseRunResult result) {
        List<TestCaseRunResult> toFlush = null;
        buffer.lock.lock();
        try {
            buffer.buffer.add(result);
            if (buffer.buffer.size() >= buffer.batchSize) {
                toFlush = new ArrayList<>(buffer.buffer);
                buffer.buffer.clear();
            }
        } finally {
            buffer.lock.unlock();
        }

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
        log.debug("Flushed {} results for run {} (total: {})", batch.size(), buffer.runId, flushed);

        try {
            sseService.notifyProgress(buffer.runId, buffer.suiteId, flushed, buffer.totalCases);
        } catch (Exception e) {
            log.debug("Failed to send progress SSE for run {}: {}", buffer.runId, e.getMessage(), e);
        }
    }
}
