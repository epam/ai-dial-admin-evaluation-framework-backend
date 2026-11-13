package com.epam.aidial.evaluation.functional.tests;

import static com.epam.aidial.evaluation.functional.helper.EvalScoreFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.model.SuiteRunRank;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two analytics reads behind the suite pass-rate summary: per-run counts of {@code test_case_eval_scores}
 * rows ({@code TestCaseEvalScoreRepository.countByRunAndComputation}) and the per-suite selection of the latest
 * scored runs ({@code EvalSummaryRepository.findLatestScoredRuns}).
 */
@DisplayName("Suite pass-rate repository reads")
public abstract class SuitePassRateRepositoryFunctionalTests extends BaseFunctionalTest {

    private static final int TWO_RUNS = 2;

    @Autowired
    private TestCaseEvalScoreRepository scoreRepository;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private RunDeletionRepository runDeletionRepository;

    @Test
    @DisplayName("countByRunAndComputation tallies passed, not passed, unscored and failed rows of the given computation")
    void countsSplitIntoFourBuckets() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        scoreRepository.saveAll(List.of(
                row(runId, computationId, ExecutionStatus.SUCCESS, true),
                row(runId, computationId, ExecutionStatus.SUCCESS, true),
                row(runId, computationId, ExecutionStatus.SUCCESS, false),
                row(runId, computationId, ExecutionStatus.SUCCESS, null),
                row(runId, computationId, ExecutionStatus.FAILED, null)));

        Map<UUID, RunPassCounts> counts = scoreRepository.countByRunAndComputation(Map.of(runId, computationId));

        assertThat(counts).containsOnlyKeys(runId);
        assertThat(counts.get(runId)).isEqualTo(new RunPassCounts(runId, 2, 1, 1, 1));
    }

    @Test
    @DisplayName("countByRunAndComputation ignores rows of other computations of the same run")
    void countsOnlyTheRequestedComputation() {
        UUID runId = UUID.randomUUID();
        UUID wanted = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        scoreRepository.saveAll(List.of(
                row(runId, wanted, ExecutionStatus.SUCCESS, true),
                row(runId, other, ExecutionStatus.SUCCESS, false),
                row(runId, other, ExecutionStatus.FAILED, null)));

        RunPassCounts counts =
                scoreRepository.countByRunAndComputation(Map.of(runId, wanted)).get(runId);

        assertThat(counts).isEqualTo(new RunPassCounts(runId, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("countByRunAndComputation counts each run against its own computation in one call")
    void countsSeveralRunsAtOnce() {
        UUID firstSuiteRun = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        UUID computationA = UUID.randomUUID();
        UUID computationB = UUID.randomUUID();
        scoreRepository.saveAll(List.of(
                row(firstSuiteRun, computationA, ExecutionStatus.SUCCESS, true),
                row(runB, computationB, ExecutionStatus.FAILED, null),
                row(runB, computationA, ExecutionStatus.SUCCESS, true)));

        Map<UUID, RunPassCounts> counts =
                scoreRepository.countByRunAndComputation(Map.of(firstSuiteRun, computationA, runB, computationB));

        assertThat(counts.get(firstSuiteRun)).isEqualTo(new RunPassCounts(firstSuiteRun, 1, 0, 0, 0));
        assertThat(counts.get(runB)).isEqualTo(new RunPassCounts(runB, 0, 0, 0, 1));
    }

    @Test
    @DisplayName("countByRunAndComputation omits a run that has no rows for its computation")
    void runWithoutRowsIsAbsent() {
        UUID runId = UUID.randomUUID();

        assertThat(scoreRepository.countByRunAndComputation(Map.of(runId, UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    @DisplayName("countByRunAndComputation returns an empty map for an empty or null input")
    void emptyInputReturnsEmptyMap() {
        assertThat(scoreRepository.countByRunAndComputation(Map.of())).isEmpty();
        assertThat(scoreRepository.countByRunAndComputation(null)).isEmpty();
    }

    @Test
    @DisplayName("countByRunAndComputation excludes a soft-deleted run")
    void deletedRunIsNotCounted() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        scoreRepository.saveAll(List.of(row(runId, computationId, ExecutionStatus.SUCCESS, true)));
        runDeletionRepository.insert(runId, 1_000L);

        assertThat(scoreRepository.countByRunAndComputation(Map.of(runId, computationId)))
                .isEmpty();
    }

    @Test
    @DisplayName("findLatestScoredRuns ranks a suite's runs newest first and keeps only the requested number")
    void ranksNewestFirstAndKeepsTwo() {
        UUID suiteId = UUID.randomUUID();
        UUID oldest = scoredRun(suiteId, 1_000L);
        UUID middle = scoredRun(suiteId, 2_000L);
        UUID newest = scoredRun(suiteId, 3_000L);

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks)
                .extracting(SuiteRunRank::suiteId, SuiteRunRank::runId, SuiteRunRank::rank)
                .containsExactlyInAnyOrder(tuple(suiteId, newest, 1), tuple(suiteId, middle, 2));
        assertThat(ranks).extracting(SuiteRunRank::runId).doesNotContain(oldest);
    }

    @Test
    @DisplayName("findLatestScoredRuns skips a run that has eval summaries but no score rows")
    void skipsRunWithoutScoreRows() {
        UUID suiteId = UUID.randomUUID();
        UUID scored = scoredRun(suiteId, 1_000L);
        UUID unscoredNewer = UUID.randomUUID();
        seedSummary(suiteId, unscoredNewer, UUID.randomUUID(), 5_000L);

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks).extracting(SuiteRunRank::suiteId, SuiteRunRank::runId, SuiteRunRank::rank).containsExactly(tuple(suiteId, scored, 1));
    }

    @Test
    @DisplayName("findLatestScoredRuns breaks a created_at tie on the larger run id")
    void breaksTieOnLargerRunId() {
        UUID suiteId = UUID.randomUUID();
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID larger = UUID.fromString("00000000-0000-0000-0000-000000000002");
        scoredRun(suiteId, smaller, 4_000L);
        scoredRun(suiteId, larger, 4_000L);

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks)
                .extracting(SuiteRunRank::suiteId, SuiteRunRank::runId, SuiteRunRank::rank)
                .containsExactlyInAnyOrder(tuple(suiteId, larger, 1), tuple(suiteId, smaller, 2));
    }

    @Test
    @DisplayName("findLatestScoredRuns excludes a soft-deleted run")
    void deletedRunIsNotSelected() {
        UUID suiteId = UUID.randomUUID();
        UUID kept = scoredRun(suiteId, 1_000L);
        UUID deleted = scoredRun(suiteId, 2_000L);
        runDeletionRepository.insert(deleted, 9_000L);

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks).extracting(SuiteRunRank::suiteId, SuiteRunRank::runId, SuiteRunRank::rank).containsExactly(tuple(suiteId, kept, 1));
    }

    @Test
    @DisplayName("findLatestScoredRuns ranks each suite on its own in one call and omits a suite with no scored run")
    void ranksEachSuiteSeparately() {
        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        UUID suiteWithoutRuns = UUID.randomUUID();
        UUID firstSuiteRun = scoredRun(suiteA, 7_000L);
        UUID secondSuiteOlder = scoredRun(suiteB, 1_000L);
        UUID secondSuiteNewer = scoredRun(suiteB, 2_000L);

        List<SuiteRunRank> ranks =
                evalSummaryRepository.findLatestScoredRuns(List.of(suiteA, suiteB, suiteWithoutRuns), TWO_RUNS);

        assertThat(ranks).extracting(SuiteRunRank::suiteId, SuiteRunRank::runId, SuiteRunRank::rank).containsExactlyInAnyOrder(
                        tuple(suiteA, firstSuiteRun, 1),
                        tuple(suiteB, secondSuiteNewer, 1),
                        tuple(suiteB, secondSuiteOlder, 2));
    }

    @Test
    @DisplayName("findLatestScoredRuns uses the newest summary row of a run as its position")
    void usesNewestSummaryRowOfRun() {
        UUID suiteId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID earlyThenLate = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        seedSummary(suiteId, earlyThenLate, computationId, 1_000L);
        seedSummary(suiteId, earlyThenLate, computationId, 9_000L);
        seedSummary(suiteId, middle, computationId, 5_000L);
        scoreRepository.saveAll(List.of(
                row(earlyThenLate, computationId, ExecutionStatus.SUCCESS, true),
                row(middle, computationId, ExecutionStatus.SUCCESS, true)));

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks).extracting(SuiteRunRank::suiteId, SuiteRunRank::runId, SuiteRunRank::rank).containsExactlyInAnyOrder(
                        tuple(suiteId, earlyThenLate, 1), tuple(suiteId, middle, 2));
    }

    @Test
    @DisplayName("findLatestScoredRuns returns each run's latest computation, the newest computed_at_ms")
    void returnsLatestComputationOfEachRun() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID olderComputation = UUID.randomUUID();
        UUID latestComputation = UUID.randomUUID();
        seedSummary(suiteId, runId, olderComputation, 1_000L, 1_000L);
        seedSummary(suiteId, runId, latestComputation, 2_000L, 8_000L);
        scoreRepository.saveAll(List.of(row(runId, latestComputation, ExecutionStatus.SUCCESS, true)));

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks).extracting(SuiteRunRank::runId, SuiteRunRank::computationId)
                .containsExactly(tuple(runId, latestComputation));
    }

    @Test
    @DisplayName("findLatestScoredRuns breaks a computed_at_ms tie on the smaller computation id")
    void breaksComputationTieOnSmallerId() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID larger = UUID.fromString("00000000-0000-0000-0000-000000000002");
        seedSummary(suiteId, runId, larger, 5_000L, 5_000L);
        seedSummary(suiteId, runId, smaller, 5_000L, 5_000L);
        scoreRepository.saveAll(List.of(row(runId, smaller, ExecutionStatus.SUCCESS, true)));

        List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(List.of(suiteId), TWO_RUNS);

        assertThat(ranks).extracting(SuiteRunRank::computationId).containsExactly(smaller);
    }

    @Test
    @DisplayName("findLatestScoredRuns returns an empty list for an empty or null suite list")
    void emptyInputReturnsEmptyList() {
        assertThat(evalSummaryRepository.findLatestScoredRuns(List.of(), TWO_RUNS))
                .isEmpty();
        assertThat(evalSummaryRepository.findLatestScoredRuns(null, TWO_RUNS)).isEmpty();
    }

    /** Seeds one eval summary and one score row for a new run of the suite; returns the run id. */
    private UUID scoredRun(UUID suiteId, long createdAtMs) {
        return scoredRun(suiteId, UUID.randomUUID(), createdAtMs);
    }

    private UUID scoredRun(UUID suiteId, UUID runId, long createdAtMs) {
        UUID computationId = UUID.randomUUID();
        seedSummary(suiteId, runId, computationId, createdAtMs);
        scoreRepository.saveAll(List.of(row(runId, computationId, ExecutionStatus.SUCCESS, true)));
        return runId;
    }

    private void seedSummary(UUID suiteId, UUID runId, UUID computationId, long createdAtMs) {
        seedSummary(suiteId, runId, computationId, createdAtMs, createdAtMs);
    }

    private void seedSummary(UUID suiteId, UUID runId, UUID computationId, long createdAtMs, long computedAtMs) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("case")
                .createdAtMs(createdAtMs)
                .computedAtMs(computedAtMs)
                .build());
    }
}
