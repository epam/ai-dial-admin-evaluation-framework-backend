package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.analytics.RunPassCountReconciliationProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunPassCountRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.analytics.RunPassCountReconciliationJob;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reconciliation of missing pass-count records, invoked directly (the scheduled job is disabled in the test
 * configuration). The job pages through every COMPLETED run in meta, so the assertions look at the runs seeded
 * here, never at global totals.
 */
@DisplayName("Run pass count reconciliation")
public abstract class RunPassCountReconciliationFunctionalTests extends BaseFunctionalTest {

    private static final long OLD = 1_000L;

    @Autowired
    private RunPassCountReconciliationJob job;

    @Autowired
    private RunPassCountReconciliationProperties properties;

    @Autowired
    private RunPassCountRepository runPassCountRepository;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    private boolean originalEnabled;
    private int originalBatchSize;
    private long originalMinAge;
    private UUID suiteId;

    @BeforeEach
    void setUp() {
        originalEnabled = properties.isEnabled();
        originalBatchSize = properties.getBatchSize();
        originalMinAge = properties.getMinComputationAgeMs();
        properties.setEnabled(true);
        analyticsTestDataHelper.cleanupRunPassCounts();
        final TestSuite suite = metaTestDataHelper.createTestSuite("reconcile-" + UUID.randomUUID());
        suiteId = suite.getId();
    }

    @AfterEach
    void restoreProperties() {
        properties.setEnabled(originalEnabled);
        properties.setBatchSize(originalBatchSize);
        properties.setMinComputationAgeMs(originalMinAge);
    }

    private UUID completedRun() {
        return metaTestDataHelper
                .createRunWithStatus(suiteId, RunStatus.COMPLETED)
                .getId();
    }

    private void summary(UUID runId, UUID computationId, ExecutionStatus status, long computedAt) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("case-" + UUID.randomUUID())
                .executionStatus(status.name())
                .createdAtMs(computedAt)
                .computedAtMs(computedAt)
                .build());
    }

    private List<RecordedComputation> recorded(UUID runId) {
        return runPassCountRepository.findRecordedComputations(List.of(runId));
    }

    private RunPassCount latest(UUID runId) {
        return runPassCountRepository.findLatestRanked(List.of(suiteId), 50).get(suiteId).stream()
                .filter(r -> r.getTestSuiteRunId().equals(runId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("a completed run with eval summaries and no record gets one for its latest computation")
    void recordsRunWithoutRecord() {
        final UUID runId = completedRun();
        final UUID computationId = UUID.randomUUID();
        summary(runId, computationId, ExecutionStatus.SUCCESS, OLD);
        summary(runId, computationId, ExecutionStatus.FAILED, OLD);

        job.reconcile();

        assertThat(recorded(runId)).containsExactly(new RecordedComputation(runId, computationId));
        assertThat(latest(runId).getPassedCount()).isEqualTo(1);
        assertThat(latest(runId).getTotalCount()).isEqualTo(2);
        assertThat(latest(runId).getRunCreatedAtMs())
                .isEqualTo(metaTestDataHelper.findRun(runId).orElseThrow().getCreatedAt());
    }

    @Test
    @DisplayName("a newer computation appended to a recorded run gets its own record, which readers use")
    void recordsAppendedComputation() {
        final UUID runId = completedRun();
        final UUID first = UUID.randomUUID();
        final UUID appended = UUID.randomUUID();
        summary(runId, first, ExecutionStatus.FAILED, OLD);
        job.reconcile();
        summary(runId, appended, ExecutionStatus.SUCCESS, OLD + 500);

        job.reconcile();

        assertThat(recorded(runId))
                .containsExactlyInAnyOrder(
                        new RecordedComputation(runId, first), new RecordedComputation(runId, appended));
        assertThat(latest(runId).getComputationId()).isEqualTo(appended);
        assertThat(latest(runId).getPassedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a computation younger than the minimum age is left for a later pass")
    void youngComputationLeftAlone() {
        final UUID runId = completedRun();
        summary(runId, UUID.randomUUID(), ExecutionStatus.SUCCESS, System.currentTimeMillis());

        job.reconcile();

        assertThat(recorded(runId)).isEmpty();
    }

    @Test
    @DisplayName("a second pass changes nothing")
    void secondPassChangesNothing() {
        final UUID runId = completedRun();
        summary(runId, UUID.randomUUID(), ExecutionStatus.SUCCESS, OLD);
        job.reconcile();
        final long rowsAfterFirst = analyticsTestDataHelper.countRunPassCounts();

        final int writtenBySecond = job.reconcile();

        assertThat(writtenBySecond).isZero();
        assertThat(analyticsTestDataHelper.countRunPassCounts()).isEqualTo(rowsAfterFirst);
    }

    @Test
    @DisplayName("a legacy run without eval summaries gets exactly one 0-of-0 record and the second pass leaves it")
    void legacyRunGetsOneNullComputationRecord() {
        final UUID runId = completedRun();

        job.reconcile();
        final int second = job.reconcile();

        assertThat(recorded(runId)).containsExactly(new RecordedComputation(runId, null));
        assertThat(latest(runId).getTotalCount()).isZero();
        assertThat(latest(runId).getComputedAtMs()).isZero();
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("runs that are not COMPLETED are skipped")
    void notCompletedRunsSkipped() {
        final List<UUID> runIds = List.of(
                metaTestDataHelper
                        .createRunWithStatus(suiteId, RunStatus.RUNNING)
                        .getId(),
                metaTestDataHelper
                        .createRunWithStatus(suiteId, RunStatus.FAILED)
                        .getId(),
                metaTestDataHelper
                        .createRunWithStatus(suiteId, RunStatus.CANCELLED)
                        .getId());
        runIds.forEach(id -> summary(id, UUID.randomUUID(), ExecutionStatus.SUCCESS, OLD));

        job.reconcile();

        assertThat(runPassCountRepository.findRecordedComputations(runIds)).isEmpty();
    }

    @Test
    @DisplayName("when disabled, no pass runs")
    void disabledDoesNothing() {
        final UUID runId = completedRun();
        summary(runId, UUID.randomUUID(), ExecutionStatus.SUCCESS, OLD);
        properties.setEnabled(false);

        final int written = job.reconcile();

        assertThat(written).isZero();
        assertThat(recorded(runId)).isEmpty();
    }

    @Test
    @DisplayName("every run is reached when the runs span several batches")
    void pagesThroughSeveralBatches() {
        properties.setBatchSize(2);
        final List<UUID> runIds =
                List.of(completedRun(), completedRun(), completedRun(), completedRun(), completedRun());
        runIds.forEach(id -> summary(id, UUID.randomUUID(), ExecutionStatus.SUCCESS, OLD));

        job.reconcile();

        assertThat(runPassCountRepository.findRecordedComputations(runIds))
                .extracting(RecordedComputation::testSuiteRunId)
                .containsExactlyInAnyOrderElementsOf(runIds);
    }
}
