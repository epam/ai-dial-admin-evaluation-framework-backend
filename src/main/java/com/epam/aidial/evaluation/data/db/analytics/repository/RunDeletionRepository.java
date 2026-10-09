package com.epam.aidial.evaluation.data.db.analytics.repository;

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
}
