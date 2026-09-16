package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassRateStats;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRunRef;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Repository-level tests for the two reads behind the suite pass-rate endpoint: the meta
 * "recent runs of a suite" window ({@link TestSuiteRunRepository#findRecentByTestSuiteId}) and the
 * analytics latest-computation pass-rate aggregate
 * ({@link EvalSummaryRepository#countPassRateByLatestComputation}), plus the tie-break rule the latter
 * shares with {@link EvalSummaryRepository#findLatestComputationId}.
 */
@DisplayName("Pass Rate Repository Functional Tests")
public abstract class PassRateRepositoryFunctionalTests extends BaseFunctionalTest {

    private static final long BASE_CREATED_AT_MS = 1_700_000_000_000L;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Autowired
    private TestSuiteRunRepository testSuiteRunRepository;

    private UUID suiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupEvalScores();
        suiteId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should count only the run's newest computation, ignoring an older computation's rows")
    void shouldCountOnlyNewestComputation() {
        UUID runId = UUID.randomUUID();
        UUID olderComputation = UUID.randomUUID();
        UUID newerComputation = UUID.randomUUID();
        long olderComputedAtMs = BASE_CREATED_AT_MS;
        long newerComputedAtMs = BASE_CREATED_AT_MS + 60_000L;

        // Older computation: two SUCCESS rows with no score data at all - must not be counted.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, olderComputation, "StaleA", ExecutionStatus.SUCCESS.name(), 100L, olderComputedAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, olderComputation, "StaleB", ExecutionStatus.SUCCESS.name(), 100L, olderComputedAtMs);

        // Newer computation: a different status mix, so a wrong (older) pick is numerically detectable.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, newerComputation, "Failing", ExecutionStatus.FAILED.name(), 100L, newerComputedAtMs);
        UUID successId = analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, newerComputation, "Passing", ExecutionStatus.SUCCESS.name(), 100L, newerComputedAtMs);
        analyticsTestDataHelper.createEvalScore(successId, 1.0, true, newerComputedAtMs);

        assertThat(evalSummaryRepository.findLatestComputationId(runId)).contains(newerComputation);

        List<RunPassRateStats> stats = evalSummaryRepository.countPassRateByLatestComputation(List.of(runId));
        assertThat(stats).hasSize(1);
        RunPassRateStats runStats = stats.get(0);
        assertThat(runStats.computationId()).isEqualTo(newerComputation);
        assertThat(runStats.total()).isEqualTo(2L);
        assertThat(runStats.failed()).isEqualTo(1L);
        assertThat(runStats.successPassed()).isEqualTo(1L);
        assertThat(runStats.successNotPassed()).isEqualTo(0L);
        assertThat(runStats.successNoVerdict()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should break a computed_at_ms tie by the greater computation_id under text ordering")
    void shouldBreakComputedAtMsTieByTextOrderOfComputationId() {
        // Canonical strings differ in their first hex character, chosen so String.compareTo and
        // java.util.UUID.compareTo disagree: the leading 'f' nibble sets the sign bit of mostSigBits,
        // making the 'f...' UUID compare as LESS via UUID.compareTo despite being the greater string.
        UUID computationZeroPrefixed = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID computationHexPrefixed = UUID.fromString("f0000000-0000-4000-8000-000000000002");
        UUID expectedWinner = List.of(computationZeroPrefixed, computationHexPrefixed).stream()
                .max(Comparator.comparing(UUID::toString))
                .orElseThrow();

        UUID runId = UUID.randomUUID();
        long tiedComputedAtMs = BASE_CREATED_AT_MS;

        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationZeroPrefixed,
                "ZeroPrefixed",
                ExecutionStatus.SUCCESS.name(),
                100L,
                tiedComputedAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationHexPrefixed,
                "HexPrefixed",
                ExecutionStatus.SUCCESS.name(),
                100L,
                tiedComputedAtMs);

        assertThat(evalSummaryRepository.findLatestComputationId(runId)).contains(expectedWinner);

        List<RunPassRateStats> stats = evalSummaryRepository.countPassRateByLatestComputation(List.of(runId));
        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).computationId()).isEqualTo(expectedWinner);
    }

    @Test
    @DisplayName("Should partition every row into exactly one bucket and sum the buckets to total")
    void shouldPartitionEveryRowIntoExactlyOneBucket() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        long computedAtMs = BASE_CREATED_AT_MS;

        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "Failed", ExecutionStatus.FAILED.name(), 100L, computedAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "TimedOut", ExecutionStatus.TIMEOUT.name(), 100L, computedAtMs);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "Errored", ExecutionStatus.ERROR.name(), 100L, computedAtMs);

        UUID passedId = analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "Passed", ExecutionStatus.SUCCESS.name(), 100L, computedAtMs);
        analyticsTestDataHelper.createEvalScore(passedId, 1.0, true, computedAtMs);

        UUID notPassedId = analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "NotPassed", ExecutionStatus.SUCCESS.name(), 100L, computedAtMs);
        analyticsTestDataHelper.createEvalScore(notPassedId, 0.0, false, computedAtMs);

        UUID nullVerdictId = analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "NullVerdict", ExecutionStatus.SUCCESS.name(), 100L, computedAtMs);
        analyticsTestDataHelper.createEvalScore(nullVerdictId, null, null, computedAtMs);

        // No score row at all - the other "no verdict" cause.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "NoScoreRow", ExecutionStatus.SUCCESS.name(), 100L, computedAtMs);

        List<RunPassRateStats> stats = evalSummaryRepository.countPassRateByLatestComputation(List.of(runId));
        assertThat(stats).hasSize(1);
        RunPassRateStats runStats = stats.get(0);

        assertThat(runStats.failed()).isEqualTo(3L);
        assertThat(runStats.successPassed()).isEqualTo(1L);
        assertThat(runStats.successNotPassed()).isEqualTo(1L);
        assertThat(runStats.successNoVerdict()).isEqualTo(2L);
        assertThat(runStats.total()).isEqualTo(7L);
        assertThat(runStats.failed()
                        + runStats.successPassed()
                        + runStats.successNotPassed()
                        + runStats.successNoVerdict())
                .isEqualTo(runStats.total());
    }

    @Test
    @DisplayName("Should omit a run id with no eval summaries from the result")
    void shouldOmitRunWithNoSummaries() {
        UUID runWithSummaries = UUID.randomUUID();
        UUID runWithoutSummaries = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        long computedAtMs = BASE_CREATED_AT_MS;

        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runWithSummaries,
                computationId,
                "Present",
                ExecutionStatus.SUCCESS.name(),
                100L,
                computedAtMs);

        List<RunPassRateStats> stats =
                evalSummaryRepository.countPassRateByLatestComputation(List.of(runWithSummaries, runWithoutSummaries));

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).runId()).isEqualTo(runWithSummaries);
    }

    @Test
    @DisplayName("Should return an empty list when runIds is empty")
    void shouldReturnEmptyListForEmptyRunIds() {
        List<RunPassRateStats> stats = evalSummaryRepository.countPassRateByLatestComputation(List.of());

        assertThat(stats).isEmpty();
    }

    @Test
    @DisplayName("Should return a suite's runs of any status newest-first, truncated to the limit")
    void shouldReturnRecentRunsNewestFirstTruncatedToLimit() {
        UUID testSuiteId = metaTestDataHelper
                .createTestSuite("Pass Rate Repo Suite " + UUID.randomUUID())
                .getId();

        TestSuiteRun oldest = metaTestDataHelper.createTestSuiteRun(testSuiteId, RunStatus.COMPLETED);
        TestSuiteRun middle = metaTestDataHelper.createTestSuiteRun(testSuiteId, RunStatus.FAILED);
        TestSuiteRun newest = metaTestDataHelper.createTestSuiteRun(testSuiteId, RunStatus.RUNNING);

        metaTestDataHelper.forceRunCreatedAt(oldest.getId(), BASE_CREATED_AT_MS);
        metaTestDataHelper.forceRunCreatedAt(middle.getId(), BASE_CREATED_AT_MS + 10_000L);
        metaTestDataHelper.forceRunCreatedAt(newest.getId(), BASE_CREATED_AT_MS + 20_000L);

        List<TestSuiteRunRef> topTwo = testSuiteRunRepository.findRecentByTestSuiteId(testSuiteId, 2);

        assertThat(topTwo).hasSize(2);
        assertThat(topTwo).extracting(TestSuiteRunRef::id).containsExactly(newest.getId(), middle.getId());
        assertThat(topTwo.get(0).status()).isEqualTo(RunStatus.RUNNING.name());
        assertThat(topTwo.get(0).createdAtMs()).isEqualTo(BASE_CREATED_AT_MS + 20_000L);
        assertThat(topTwo.get(1).status()).isEqualTo(RunStatus.FAILED.name());

        List<TestSuiteRunRef> all = testSuiteRunRepository.findRecentByTestSuiteId(testSuiteId, 10);
        assertThat(all).extracting(TestSuiteRunRef::id).containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    @Test
    @DisplayName("Should break equal created_at_ms in the meta window by the greater run id")
    void shouldBreakRecentRunsCreatedAtTieByRunId() {
        UUID testSuiteId = metaTestDataHelper
                .createTestSuite("Pass Rate Repo Tie Suite " + UUID.randomUUID())
                .getId();

        TestSuiteRun runA = metaTestDataHelper.createTestSuiteRun(testSuiteId, RunStatus.COMPLETED);
        TestSuiteRun runB = metaTestDataHelper.createTestSuiteRun(testSuiteId, RunStatus.COMPLETED);
        metaTestDataHelper.forceRunCreatedAt(runA.getId(), BASE_CREATED_AT_MS);
        metaTestDataHelper.forceRunCreatedAt(runB.getId(), BASE_CREATED_AT_MS);

        UUID expectedFirst = Comparator.comparing(UUID::toString).compare(runA.getId(), runB.getId()) > 0
                ? runA.getId()
                : runB.getId();

        Optional<TestSuiteRunRef> first = testSuiteRunRepository.findRecentByTestSuiteId(testSuiteId, 1).stream()
                .findFirst();

        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo(expectedFirst);
    }
}
