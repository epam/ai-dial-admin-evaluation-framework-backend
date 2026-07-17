package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface TestCaseRunInputRepository {

    void insertBatch(List<TestCaseRunInput> inputs);

    List<TestCaseRunInput> findByRunId(UUID runId, int offset, int limit);

    long countByRunId(UUID runId);

    boolean existsByRunId(UUID runId);

    /**
     * Deletes all input rows for the given run. Used by the snapshot phase to make input-writing
     * idempotent (clear any leftovers from a prior failed attempt before re-writing).
     */
    void deleteByRunId(UUID runId);

    int deleteByRunIdsInTerminalStateOlderThan(Duration retention);
}
