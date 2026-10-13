package com.epam.aidial.evaluation.data.db.analytics.repository;

import java.util.List;
import java.util.UUID;

public interface RunDeletionRepository {

    /**
     * Idempotently inserts a tombstone row for a deleted run.
     * If a row with the same testSuiteRunId already exists, the operation succeeds silently
     * (no update, no error).
     *
     * @param testSuiteRunId the run ID to mark as deleted
     * @param deletedAtMs    epoch milliseconds when the run was deleted
     */
    void insert(UUID testSuiteRunId, long deletedAtMs);

    /**
     * Idempotently inserts tombstone rows for several deleted runs in a single batch statement,
     * all sharing the same deletion timestamp. Already-tombstoned runs are skipped silently.
     *
     * @param testSuiteRunIds the run IDs to mark as deleted
     * @param deletedAtMs     epoch milliseconds when the runs were deleted
     */
    void insertAll(List<UUID> testSuiteRunIds, long deletedAtMs);
}
