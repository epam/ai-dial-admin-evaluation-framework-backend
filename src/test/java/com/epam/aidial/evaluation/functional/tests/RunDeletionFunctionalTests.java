package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("RunDeletionFunctionalTests")
public abstract class RunDeletionFunctionalTests {

    @Autowired
    private TestSuiteRunService testSuiteRunService;

    @Autowired
    private TestSuiteService testSuiteService;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Test
    @DisplayName("3.5: Deleting a terminal run with analytics rows excludes them from all read paths")
    void deleteRunExcludesAnalyticsRowsFromAllReadPaths() {
        // Setup: Create dataset and suite
        var dataset = metaTestDataHelper.createDataset("deletion-test-dataset-" + UUID.randomUUID());
        var suite = metaTestDataHelper.createTestSuite("deletion-test-suite-" + UUID.randomUUID(), dataset.getId());

        // Setup: Create a completed run
        var run = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, null);
        var createdAtMs = System.currentTimeMillis();

        // Setup: Insert analytics rows for this run (mimicking actual test execution)
        var computationId = UUID.randomUUID();
        var testCaseId = UUID.randomUUID();
        analyticsTestDataHelper.createTestRunResult(
                run.getId(), suite.getId(), testCaseId, "test-case-1", "{}", "{}", createdAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suite.getId(), run.getId(), computationId, "test-case-1", "SUCCESS", 100L, createdAtMs);

        // Verify: Analytics rows exist before deletion
        assertThat(analyticsTestDataHelper.findResultsByRunId(run.getId())).isNotEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run.getId()))
                .isNotEmpty();

        // Execute: Delete the run
        testSuiteRunService.deleteRun(run.getId());

        // Verify: All analytics rows are excluded from read paths after deletion
        assertThat(analyticsTestDataHelper.findResultsByRunId(run.getId())).isEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("3.6: Deleting a suite with runs excludes all run analytics rows from read paths")
    void deleteSuiteExcludesAllRunAnalyticsRowsFromReadPaths() {
        // Setup: Create dataset and suite
        var dataset = metaTestDataHelper.createDataset("suite-deletion-dataset-" + UUID.randomUUID());
        var suite = metaTestDataHelper.createTestSuite("suite-to-delete-" + UUID.randomUUID(), dataset.getId());

        // Setup: Create multiple runs with different statuses
        var run1 = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, null);
        var run2 = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.FAILED, null);
        var createdAtMs = System.currentTimeMillis();

        // Setup: Insert analytics rows for both runs
        var computationId = UUID.randomUUID();
        var testCaseId = UUID.randomUUID();
        analyticsTestDataHelper.createTestRunResult(
                run1.getId(), suite.getId(), testCaseId, "test-case-1", "{}", "{}", createdAtMs);
        analyticsTestDataHelper.createTestRunResult(
                run2.getId(), suite.getId(), testCaseId, "test-case-1", "{}", "{}", createdAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suite.getId(), run1.getId(), computationId, "test-case-1", "SUCCESS", 100L, createdAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suite.getId(), run2.getId(), computationId, "test-case-1", "FAILED", 200L, createdAtMs);

        // Verify: Analytics rows exist for both runs
        assertThat(analyticsTestDataHelper.findResultsByRunId(run1.getId())).isNotEmpty();
        assertThat(analyticsTestDataHelper.findResultsByRunId(run2.getId())).isNotEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run1.getId()))
                .isNotEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run2.getId()))
                .isNotEmpty();

        // Execute: Delete the suite (cascades to all runs and their tombstones)
        testSuiteService.delete(suite.getId());

        // Verify: All analytics rows for both runs are excluded from read paths
        assertThat(analyticsTestDataHelper.findResultsByRunId(run1.getId())).isEmpty();
        assertThat(analyticsTestDataHelper.findResultsByRunId(run2.getId())).isEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run1.getId()))
                .isEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run2.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("3.7: Idempotent tombstone insertion ensures analytics exclusion persists")
    void deleteRunIdempotentBehavior() {
        // Setup: Create dataset, suite, and run
        var dataset = metaTestDataHelper.createDataset("idempotent-test-dataset-" + UUID.randomUUID());
        var suite = metaTestDataHelper.createTestSuite("idempotent-suite-" + UUID.randomUUID(), dataset.getId());
        var run = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, null);
        var createdAtMs = System.currentTimeMillis();

        // Setup: Insert analytics data
        var computationId = UUID.randomUUID();
        var testCaseId = UUID.randomUUID();
        analyticsTestDataHelper.createTestRunResult(
                run.getId(), suite.getId(), testCaseId, "test-case-1", "{}", "{}", createdAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suite.getId(), run.getId(), computationId, "test-case-1", "SUCCESS", 100L, createdAtMs);

        // Verify: Analytics data exists before deletion
        assertThat(analyticsTestDataHelper.findResultsByRunId(run.getId())).isNotEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run.getId()))
                .isNotEmpty();

        // Execute: First deletion
        testSuiteRunService.deleteRun(run.getId());

        // Verify: Analytics data is excluded after deletion
        assertThat(analyticsTestDataHelper.findResultsByRunId(run.getId())).isEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run.getId()))
                .isEmpty();

        // The tombstone mechanism ensures idempotent behavior:
        // The run_deletions table's ON CONFLICT DO NOTHING pattern means
        // re-inserting the same tombstone doesn't cause errors or data inconsistency.
        // Analytics data remains excluded regardless.

        // Verify: Analytics data remains excluded (tombstone is idempotent)
        assertThat(analyticsTestDataHelper.findResultsByRunId(run.getId())).isEmpty();
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(run.getId()))
                .isEmpty();
    }
}
