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

    void updateToRunning(UUID id, long startedAt, long updatedAt);

    void updateToCompleted(UUID id, long completedAt, long updatedAt);

    void updateToFailed(UUID id, String errorMessage, String errorDetails, long completedAt, long updatedAt);

    void updateToCancelled(UUID id, long completedAt, long updatedAt);

    void updateTestRunName(UUID id, String newName);

    void updateSuiteSnapshot(UUID id, String snapshotJson, long updatedAt);

    void updateNumberOfTestCases(UUID id, int numberOfTestCases, long updatedAt);

    boolean deleteById(UUID id);

    int failOrphanedRuns(List<String> orphanedStatuses, String failedStatus, String errorMessage, String errorDetails);

    long nextRunNameSequenceValue();
}
