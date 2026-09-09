package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteRunRepository {

    TestSuiteRun save(TestSuiteRun run);

    Optional<TestSuiteRun> findById(UUID id);

    Optional<TestSuiteRun> findLatestByTestSuiteId(UUID testSuiteId);

    Page<TestSuiteRun> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    int countByStatuses(List<String> statuses);

    int countByTestSuiteIdAndStatuses(UUID testSuiteId, List<String> statuses);

    int updateStatusOptimistic(UUID id, String newStatus, String expectedStatus);

    /** Guarded {@code WHERE status = 'PENDING'}. Returns the affected-row count. */
    int updateToRunning(UUID id, long startedAt, long updatedAt);

    /** Guarded {@code WHERE status = 'RUNNING'}. Returns the affected-row count. */
    int updateToCompleted(UUID id, long completedAt, long updatedAt);

    /** Guarded {@code WHERE status IN ('PENDING', 'RUNNING', 'CANCELLING')}. Returns the affected-row count. */
    int updateToFailed(UUID id, String errorMessage, String errorDetails, long completedAt, long updatedAt);

    /** Guarded {@code WHERE status IN ('RUNNING', 'CANCELLING')}. Returns the affected-row count. */
    int updateToCancelled(UUID id, long completedAt, long updatedAt);

    /**
     * Transitions RUNNING -> CANCELLING (no {@code completed_at}). {@code updated_at_ms} comes from
     * {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}. Returns
     * the affected-row count.
     */
    int markCancelling(UUID id);

    /**
     * Finalizes orphaned CANCELLING runs as CANCELLED on startup (mirrors {@link #failOrphanedRuns}).
     * {@code completed_at_ms} / {@code updated_at_ms} come from
     * {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}. Returns
     * the affected-row count.
     */
    int cancelOrphanedCancellingRuns();

    void updateTestRunName(UUID id, String newName);

    void updateSuiteSnapshot(UUID id, String snapshotJson, long updatedAt);

    void updateNumberOfTestCases(UUID id, int numberOfTestCases, long updatedAt);

    boolean deleteById(UUID id);

    int failOrphanedRuns(List<String> orphanedStatuses, String failedStatus, String errorMessage, String errorDetails);

    long nextRunNameSequenceValue();
}
