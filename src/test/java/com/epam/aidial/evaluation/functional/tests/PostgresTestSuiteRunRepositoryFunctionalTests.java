package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunReconciliation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("PostgresTestSuiteRunRepository tests")
public abstract class PostgresTestSuiteRunRepositoryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteRunRepository runRepository;

    @Autowired
    private TestSuiteRunReconciliation reconciliation;

    @Test
    @DisplayName("findAll excludes suite_snapshot column; suiteSnapshot field is null in list results")
    void findAllExcludesSuiteSnapshot() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Repo Test Suite List").getId();
        TestSuiteRun saved = metaTestDataHelper.createTestSuiteRun(suiteId);

        // Set a snapshot so we can verify it's excluded from list queries
        runRepository.updateSuiteSnapshot(
                saved.getId(), "{\"snapshotVersion\":\"1\",\"suiteType\":\"DEPLOYMENT\"}", System.currentTimeMillis());

        Page<TestSuiteRun> page = runRepository.findAll(PageRequest.of(0, 100), List.of(), false);

        Optional<TestSuiteRun> found = page.getContent().stream()
                .filter(r -> r.getId().equals(saved.getId()))
                .findFirst();
        assertThat(found).isPresent();
        // List tier must NOT include suite_snapshot
        assertThat(found.get().getSuiteSnapshot()).isNull();
    }

    @Test
    @DisplayName("findById includes suite_snapshot column with stored snapshot JSON")
    void findByIdIncludesSuiteSnapshot() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Repo Test Suite Detail").getId();
        TestSuiteRun saved = metaTestDataHelper.createTestSuiteRun(suiteId);
        String snapshotJson = "{\"snapshotVersion\":\"1\",\"suiteType\":\"DEPLOYMENT\"}";

        runRepository.updateSuiteSnapshot(saved.getId(), snapshotJson, System.currentTimeMillis());

        Optional<TestSuiteRun> found = runRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSuiteSnapshot()).isNotNull();
        assertThat(found.get().getSuiteSnapshot()).contains("snapshotVersion");
    }

    @Test
    @DisplayName("findById returns null suiteSnapshot when no snapshot was set")
    void findByIdReturnsNullSuiteSnapshotWhenNotSet() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo Test Suite No Snapshot")
                .getId();
        TestSuiteRun saved = metaTestDataHelper.createLegacyTestSuiteRun(suiteId);

        Optional<TestSuiteRun> found = runRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSuiteSnapshot()).isNull();
    }

    @Test
    @DisplayName("updateSuiteSnapshot persists snapshot and is readable via findById")
    void updateSuiteSnapshotPersistsSnapshot() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Repo Test Snapshot Update").getId();
        TestSuiteRun saved = metaTestDataHelper.createTestSuiteRun(suiteId);
        String snapshotJson = "{\"snapshotVersion\":\"1\",\"suiteType\":\"MCP_TOOL\"}";

        runRepository.updateSuiteSnapshot(saved.getId(), snapshotJson, System.currentTimeMillis());

        Optional<TestSuiteRun> found = runRepository.findById(saved.getId());
        assertThat(found).isPresent();
        // Snapshot is stored as JSONB — content should be equivalent
        assertThat(found.get().getSuiteSnapshot()).contains("MCP_TOOL");
    }

    // --- Guarded status-write tests (interrupt-driven-cancellation) ---

    @Test
    @DisplayName("updateToRunning: 1-row case transitions PENDING -> RUNNING")
    void updateToRunningAffectsPendingRun() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToRunning Pending")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.PENDING);

        int affected = runRepository.updateToRunning(run.getId(), 111L, 222L);

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING.name());
        assertThat(updated.getStartedAt()).isEqualTo(111L);
    }

    @Test
    @DisplayName("updateToRunning: 0-row case leaves a non-PENDING run unchanged")
    void updateToRunningNoopWhenNotPending() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToRunning Running")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.RUNNING);

        int affected = runRepository.updateToRunning(run.getId(), 111L, 222L);

        assertThat(affected).isZero();
        TestSuiteRun unchanged = runRepository.findById(run.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RunStatus.RUNNING.name());

        // A RUNNING row left lying around would count toward other tests' active-run limits; clean it up.
        runRepository.deleteById(run.getId());
    }

    @Test
    @DisplayName("updateToCompleted: 1-row case transitions RUNNING -> COMPLETED")
    void updateToCompletedAffectsRunningRun() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToCompleted Running")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.RUNNING);

        int affected = runRepository.updateToCompleted(run.getId(), 333L, 444L);

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(updated.getCompletedAt()).isEqualTo(333L);
    }

    @Test
    @DisplayName("updateToCompleted: 0-row case leaves a PENDING run unchanged")
    void updateToCompletedNoopWhenPending() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToCompleted Pending")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.PENDING);

        int affected = runRepository.updateToCompleted(run.getId(), 333L, 444L);

        assertThat(affected).isZero();
        TestSuiteRun unchanged = runRepository.findById(run.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RunStatus.PENDING.name());

        // A PENDING row left lying around would count toward other tests' active-run limits; clean it up.
        runRepository.deleteById(run.getId());
    }

    @Test
    @DisplayName("updateToCancelled: 1-row case transitions RUNNING -> CANCELLED")
    void updateToCancelledAffectsRunningRun() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToCancelled Running")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.RUNNING);

        int affected = runRepository.updateToCancelled(run.getId(), 555L, 666L);

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.CANCELLED.name());
        assertThat(updated.getCompletedAt()).isEqualTo(555L);
    }

    @Test
    @DisplayName("updateToCancelled: 1-row case transitions CANCELLING -> CANCELLED")
    void updateToCancelledAffectsCancellingRun() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToCancelled Cancelling")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.CANCELLING);

        int affected = runRepository.updateToCancelled(run.getId(), 555L, 666L);

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("updateToCancelled: 0-row case leaves a PENDING run unchanged")
    void updateToCancelledNoopWhenPending() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToCancelled Pending")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.PENDING);

        int affected = runRepository.updateToCancelled(run.getId(), 555L, 666L);

        assertThat(affected).isZero();
        TestSuiteRun unchanged = runRepository.findById(run.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RunStatus.PENDING.name());

        // A PENDING row left lying around would count toward other tests' active-run limits; clean it up.
        runRepository.deleteById(run.getId());
    }

    @Test
    @DisplayName("updateToFailed: 1-row case transitions CANCELLING -> FAILED")
    void updateToFailedAffectsCancellingRun() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToFailed Cancelling")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.CANCELLING);

        int affected = runRepository.updateToFailed(run.getId(), "boom", "{}", 777L, 888L);

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED.name());
        assertThat(updated.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("updateToFailed: 0-row case leaves a terminal (COMPLETED) run unchanged")
    void updateToFailedNoopWhenTerminal() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo updateToFailed Terminal")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.COMPLETED);

        int affected = runRepository.updateToFailed(run.getId(), "boom", "{}", 777L, 888L);

        assertThat(affected).isZero();
        TestSuiteRun unchanged = runRepository.findById(run.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("markCancelling: 1-row case transitions RUNNING -> CANCELLING with no completedAt")
    void markCancellingAffectsRunningRun() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo markCancelling Running")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.RUNNING);

        int affected = metaTestDataHelper.markCancelling(run.getId());

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.CANCELLING.name());
        assertThat(updated.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("markCancelling: 0-row case leaves a PENDING run unchanged")
    void markCancellingNoopWhenPending() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo markCancelling Pending")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.PENDING);

        int affected = metaTestDataHelper.markCancelling(run.getId());

        assertThat(affected).isZero();
        TestSuiteRun unchanged = runRepository.findById(run.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RunStatus.PENDING.name());

        // A PENDING row left lying around would count toward other tests' active-run limits; clean it up.
        runRepository.deleteById(run.getId());
    }

    @Test
    @DisplayName("cancelOrphanedCancellingRuns: 1-row case finalizes a CANCELLING run as CANCELLED")
    void cancelOrphanedCancellingRunsFinalizesCancellingRun() {
        // Drain any CANCELLING rows left over from other tests in this class first, so the assertion
        // below reflects only the fixture created here.
        metaTestDataHelper.cancelOrphanedCancellingRuns();

        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo cancelOrphanedCancelling")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.CANCELLING);

        int affected = metaTestDataHelper.cancelOrphanedCancellingRuns();

        assertThat(affected).isEqualTo(1);
        TestSuiteRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.CANCELLED.name());
        assertThat(updated.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("cancelOrphanedCancellingRuns: 0-row case is a no-op when no CANCELLING runs exist")
    void cancelOrphanedCancellingRunsNoopWhenNoneCancelling() {
        // Drain any CANCELLING rows left over from other tests in this class first.
        metaTestDataHelper.cancelOrphanedCancellingRuns();

        int affected = metaTestDataHelper.cancelOrphanedCancellingRuns();

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("shouldFinalizeOrphanedCancellingRunAsCancelled_onStartup")
    void shouldFinalizeOrphanedCancellingRunAsCancelled_onStartup() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo Reconciliation Cancelling")
                .getId();
        TestSuiteRun run = metaTestDataHelper.createRunWithStatus(suiteId, RunStatus.CANCELLING);

        reconciliation.reconcileOrphanedRuns();

        TestSuiteRun reconciled = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(RunStatus.CANCELLED.name());
        assertThat(reconciled.getCompletedAt()).isNotNull();
        assertThat(reconciled.getErrorMessage()).isNull();
        assertThat(reconciled.getErrorDetails()).isNull();
    }
}
