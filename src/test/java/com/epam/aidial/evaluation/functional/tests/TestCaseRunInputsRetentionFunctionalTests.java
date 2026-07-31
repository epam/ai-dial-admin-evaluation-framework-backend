package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.TestCaseRunInputsRetentionJob;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("TestCaseRunInputs Retention Job Functional Tests")
public abstract class TestCaseRunInputsRetentionFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Autowired
    private TestCaseRunInputsRetentionJob retentionJob;

    @Test
    @DisplayName("retention job deletes inputs for COMPLETED runs older than retention window")
    void retentionJobDeletesExpiredCompletedRunInputs() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Retention Test Suite Completed")
                .getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();

        testCaseRunInputRepository.insertBatch(List.of(buildInput(runId, 0, "TC-1")));
        assertThat(testCaseRunInputRepository.existsByRunId(runId)).isTrue();

        // Backdate updated_at_ms far in the past (older than retention)
        backdateRunUpdatedAt(runId, System.currentTimeMillis() - 30L * 24 * 3600 * 1000);

        retentionJob.deleteExpiredInputs();

        assertThat(testCaseRunInputRepository.existsByRunId(runId)).isFalse();
    }

    @Test
    @DisplayName("retention job does not delete inputs for PENDING or RUNNING runs")
    void retentionJobDoesNotDeleteNonTerminalRunInputs() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Retention Test Suite Non-Terminal")
                .getId();
        UUID pendingRunId = metaTestDataHelper
                .createPendingRun(suiteId, "Pending Retention")
                .getId();
        UUID runningRunId = metaTestDataHelper
                .createRunningRun(suiteId, "Running Retention")
                .getId();

        testCaseRunInputRepository.insertBatch(List.of(buildInput(pendingRunId, 0, "TC-Pending")));
        testCaseRunInputRepository.insertBatch(List.of(buildInput(runningRunId, 0, "TC-Running")));

        // Backdate both runs — they should still be preserved because they're non-terminal
        backdateRunUpdatedAt(pendingRunId, System.currentTimeMillis() - 30L * 24 * 3600 * 1000);
        backdateRunUpdatedAt(runningRunId, System.currentTimeMillis() - 30L * 24 * 3600 * 1000);

        retentionJob.deleteExpiredInputs();

        assertThat(testCaseRunInputRepository.existsByRunId(pendingRunId)).isTrue();
        assertThat(testCaseRunInputRepository.existsByRunId(runningRunId)).isTrue();
    }

    @Test
    @DisplayName("retention job does not delete inputs for COMPLETED runs within retention window")
    void retentionJobDoesNotDeleteRecentCompletedRunInputs() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Retention Test Suite Recent")
                .getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();

        testCaseRunInputRepository.insertBatch(List.of(buildInput(runId, 0, "TC-Recent")));
        // updated_at_ms is set to now by default — within the 1-day retention window

        retentionJob.deleteExpiredInputs();

        assertThat(testCaseRunInputRepository.existsByRunId(runId)).isTrue();
    }

    // --- Helpers ---

    private TestCaseRunInput buildInput(UUID runId, int position, String name) {
        return TestCaseRunInput.builder()
                .runId(runId)
                .position(position)
                .testCaseId(UUID.randomUUID())
                .testCaseName(name)
                .testCaseData("{\"data\":\"" + name + "\"}")
                .build();
    }

    private void backdateRunUpdatedAt(UUID runId, long pastMs) {
        metaTestDataHelper.backdateRunUpdatedAt(runId, pastMs);
    }
}
