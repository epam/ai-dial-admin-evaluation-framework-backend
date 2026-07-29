package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreValueDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonRunDto;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputation;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end tests for {@code GET /api/v1/analytics/metric-scores/comparison}.
 *
 * <p>The anti-divergence test is the reason this class boots the real Phase-3 executor rather than seeding
 * {@code metric_score_results} by hand: a hand-seeded baseline would only prove the endpoint reproduces
 * numbers a test wrote, whereas comparing against Phase 3's own persisted output proves the two paths agree
 * on a full-overlap population — which is the whole claim behind running Phase 3's queries with one extra
 * ANDed predicate.
 */
@DisplayName("Run Comparison Functional Tests")
public abstract class RunComparisonFunctionalTests extends BaseFunctionalTest {

    private static final long CREATED_AT_MS = 1_700_000_000_000L;
    private static final long COMPUTED_AT_MS = 1_700_000_500_000L;
    private static final String OUTPUT_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";
    private static final String METRIC = "Relevancy";
    private static final String METRIC_FIELD = "Relevancy.score";

    /** Mirrors {@code analytics.comparison.max-unmatched-rows} in {@code application.yml}. */
    private static final int MAX_UNMATCHED_ROWS = 5000;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricScoreResultRepository metricScoreResultRepository;

    @Autowired
    private MetricScoreComputation phaseThreeExecutor;

    private UUID suiteId;
    private UUID runA;
    private UUID runB;
    private UUID computationA;
    private UUID computationB;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupRunMetricSnapshots();

        suiteId = metaTestDataHelper
                .createTestSuite("comparison-" + UUID.randomUUID())
                .getId();
        runA = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        runB = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        computationA = UUID.randomUUID();
        computationB = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should return the persisted full-population scores when every row matches")
    void shouldAgreeWithPersistedScoresOnFullOverlap() {
        // Identical case sets on both sides, carrying the two match-key subtleties as part of the same
        // fixture: "Foo"/"foo" differ only in case, and "Repeated" occurs at two run indices.
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "Foo", 0.25);
        seedScore(runA, computationA, "Bar", 0.75);
        seedScore(runB, computationB, "foo", 0.5);
        seedScore(runB, computationB, "Bar", 1.0);
        seedRepetition(runA, computationA, 0, 0.25);
        seedRepetition(runA, computationA, 1, 0.75);
        seedRepetition(runB, computationB, 0, 0.5);
        seedRepetition(runB, computationB, 1, 0.5);

        // Phase 3 computes over each run's entire population, which here IS the matched population.
        computePhaseThree(runA, computationA);
        computePhaseThree(runB, computationB);
        final List<MetricScoreResult> persistedA =
                metricScoreResultRepository.findByRunAndComputation(runA, computationA);
        final List<MetricScoreResult> persistedB =
                metricScoreResultRepository.findByRunAndComputation(runB, computationB);
        assertThat(persistedA).isNotEmpty();

        final RunComparisonResponseDto response = compare(runA, runB);

        final RunComparisonRunDto sideA = response.getRuns().get(0);
        final RunComparisonRunDto sideB = response.getRuns().get(1);
        assertThat(sideA.getRunId()).isEqualTo(runA);
        assertThat(sideB.getRunId()).isEqualTo(runB);

        // Every row matched, so nothing is excluded and no predicate is grafted.
        assertThat(sideA.getUnmatchedEvalSummaryIds()).isEmpty();
        assertThat(sideB.getUnmatchedEvalSummaryIds()).isEmpty();
        assertThat(sideA.getMatchedRowCount())
                .isEqualTo(sideA.getTotalRowCount())
                .isEqualTo(4L);
        assertThat(sideB.getMatchedRowCount())
                .isEqualTo(sideB.getTotalRowCount())
                .isEqualTo(4L);

        // The comparison's numbers ARE Phase 3's numbers when the populations coincide — same statistic
        // names, same metric names, same values, for both runs.
        assertThat(triples(sideA)).containsExactlyInAnyOrderElementsOf(persistedTriples(persistedA));
        assertThat(triples(sideB)).containsExactlyInAnyOrderElementsOf(persistedTriples(persistedB));

        // Nothing was persisted by the comparison: the rows are exactly the ones Phase 3 left behind.
        assertThat(persistedTriples(metricScoreResultRepository.findByRunAndComputation(runA, computationA)))
                .containsExactlyInAnyOrderElementsOf(persistedTriples(persistedA));
        assertThat(persistedTriples(metricScoreResultRepository.findByRunAndComputation(runB, computationB)))
                .containsExactlyInAnyOrderElementsOf(persistedTriples(persistedB));
    }

    @Test
    @DisplayName("Should aggregate every row sharing a duplicated match key")
    void shouldAggregateAllRowsSharingDuplicateKey() {
        // Side A holds two rows for one key — reachable through the eval-results import path, which keys
        // in-batch duplicates case-sensitively and without the turn index. A design that collapsed them
        // would silently drop one from the aggregate.
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        analyticsTestDataHelper.createEvalSummary(
                fixture(runA, computationA, "Shared", 0.25).execDurationMs(100L).build());
        analyticsTestDataHelper.createEvalSummary(
                fixture(runA, computationA, "Shared", 0.75).execDurationMs(300L).build());
        seedScore(runB, computationB, "Shared", 0.5);

        final RunComparisonResponseDto response = compare(runA, runB);
        final RunComparisonRunDto sideA = response.getRuns().get(0);
        final RunComparisonRunDto sideB = response.getRuns().get(1);

        // Both duplicate rows match, so A legitimately matches more rows than B.
        assertThat(sideA.getMatchedRowCount()).isEqualTo(2L);
        assertThat(sideB.getMatchedRowCount()).isEqualTo(1L);
        assertThat(sideA.getMatchedRowCount()).isGreaterThan(sideB.getMatchedRowCount());
        assertThat(sideA.getUnmatchedEvalSummaryIds()).isEmpty();

        // MIN and MAX pin both rows into the population individually; a collapse would make them equal.
        assertThat(score(sideA, "MIN", METRIC_FIELD)).isEqualTo(0.25);
        assertThat(score(sideA, "MAX", METRIC_FIELD)).isEqualTo(0.75);
        // The average is over both, not either one alone.
        assertThat(score(sideA, "AVG", METRIC_FIELD)).isCloseTo(0.5, within(1e-9));
        // Same for the execution-duration mean: (100 + 300) / 2.
        assertThat(sideA.getAvgExecDurationMs()).isCloseTo(200.0, within(1e-9));
    }

    @Test
    @DisplayName("Should exclude a run's non-matching rows from its own aggregates")
    void shouldExcludeNonMatchingRowsFromAggregates() {
        // Run A's cases are a strict subset of run B's. This is the first fixture with a non-empty exclusion
        // list, so it is the one that actually executes the grafted NOT (id IN …) predicate — a UUID-typed
        // `in` over a VARCHAR(36) column, which no mock can vouch for.
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "Keep1", 0.5);
        seedScore(runA, computationA, "Keep2", 0.5);
        seedScore(runB, computationB, "Keep1", 0.5);
        seedScore(runB, computationB, "Keep2", 0.5);
        // Present only in B, and given an extreme value so leaking it into B's aggregates is unmistakable.
        seedScore(runB, computationB, "OnlyInB", 9.0);

        final RunComparisonResponseDto response = compare(runA, runB);
        final RunComparisonRunDto sideA = response.getRuns().get(0);
        final RunComparisonRunDto sideB = response.getRuns().get(1);

        assertThat(sideA.getUnmatchedEvalSummaryIds()).isEmpty();
        assertThat(sideB.getUnmatchedEvalSummaryIds()).hasSize(1);
        assertThat(sideB.getTotalRowCount()).isEqualTo(3L);
        assertThat(sideB.getMatchedRowCount()).isEqualTo(2L);

        // The excluded row is gone from every statistic: MAX would be 9.0 and AVG 3.33… if the predicate
        // had not been applied, so these two assertions are what prove the exclusion reached the database.
        assertThat(score(sideB, "MAX", METRIC_FIELD)).isEqualTo(0.5);
        assertThat(score(sideB, "AVG", METRIC_FIELD)).isCloseTo(0.5, within(1e-9));
        // Both sides now describe the same two rows, so their statistics agree.
        assertThat(score(sideA, "AVG", METRIC_FIELD)).isEqualTo(score(sideB, "AVG", METRIC_FIELD));
    }

    @Test
    @DisplayName("Should report no scores and exclude every row when the runs share nothing")
    void shouldReturnNoScoresWhenNothingOverlaps() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "OnlyInA", 0.25);
        seedScore(runB, computationB, "OnlyInB", 0.75);

        final RunComparisonResponseDto response = compare(runA, runB);
        final RunComparisonRunDto sideA = response.getRuns().get(0);

        assertThat(sideA.getMatchedRowCount()).isZero();
        assertThat(sideA.getUnmatchedEvalSummaryIds()).hasSize(1);
        // No matched rows means every aggregate would be NULL, so no query is issued and no entry survives.
        assertThat(sideA.getScores()).isEmpty();
        // An average over an empty set is NULL, and the underlying column is NOT NULL, so null is unambiguous.
        assertThat(sideA.getAvgExecDurationMs()).isNull();
    }

    @Test
    @DisplayName("Should match per turn, so a longer conversation matches only its shared turns")
    void shouldMatchOnTurnIndex() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        // The same case name run as a 3-turn conversation in A and a 2-turn one in B. Durations differ per
        // turn so the average can distinguish per-turn sampling from a per-conversation one.
        seedTurn(runA, computationA, 0, 3, 0.5, 100L);
        seedTurn(runA, computationA, 1, 3, 0.5, 300L);
        seedTurn(runA, computationA, 2, 3, 9.0, 9000L);
        seedTurn(runB, computationB, 0, 2, 0.5, 100L);
        seedTurn(runB, computationB, 1, 2, 0.5, 100L);

        final RunComparisonResponseDto response = compare(runA, runB);
        final RunComparisonRunDto sideA = response.getRuns().get(0);
        final RunComparisonRunDto sideB = response.getRuns().get(1);

        // Turns 0 and 1 match; A's third turn has no counterpart, so the name alone does not carry the match.
        assertThat(sideA.getMatchedRowCount()).isEqualTo(2L);
        assertThat(sideA.getUnmatchedEvalSummaryIds()).hasSize(1);
        assertThat(sideB.getMatchedRowCount()).isEqualTo(2L);
        assertThat(sideB.getUnmatchedEvalSummaryIds()).isEmpty();
        // total_turns is not part of the key: 3-turn and 2-turn rows still match at turns 0 and 1.
        assertThat(score(sideA, "MAX", METRIC_FIELD)).isEqualTo(0.5);
        // Each turn is one sample: (100 + 300) / 2. Summing the conversation first would give 400, and
        // including the unmatched third turn would give 3133.33…
        assertThat(sideA.getAvgExecDurationMs()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("Should match per repetition, so extra repetitions of one run do not match")
    void shouldMatchOnRunIndex() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        // One test case executed with numberOfRuns 3 in A and 2 in B. Unlike the full-overlap fixture, the
        // repetition counts differ — which is the only shape that detects run_index dropping out of the
        // match key altogether, rather than merely out of the join condition.
        seedRepetition(runA, computationA, 0, 0.5);
        seedRepetition(runA, computationA, 1, 0.5);
        seedRepetition(runA, computationA, 2, 9.0);
        seedRepetition(runB, computationB, 0, 0.5);
        seedRepetition(runB, computationB, 1, 0.5);

        final RunComparisonResponseDto response = compare(runA, runB);
        final RunComparisonRunDto sideA = response.getRuns().get(0);
        final RunComparisonRunDto sideB = response.getRuns().get(1);

        // Exactly one pair per shared repetition index; A's third repetition has no counterpart.
        assertThat(sideA.getMatchedRowCount()).isEqualTo(2L);
        assertThat(sideA.getUnmatchedEvalSummaryIds()).hasSize(1);
        assertThat(sideB.getMatchedRowCount()).isEqualTo(2L);
        assertThat(sideB.getUnmatchedEvalSummaryIds()).isEmpty();
        // The unmatched repetition's outlier value stays out of A's statistics.
        assertThat(score(sideA, "MAX", METRIC_FIELD)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Should return full aggregates for a run whose scores were never persisted")
    void shouldAggregateWithoutAnyPersistedScores() {
        // No Phase 3 run at all: the comparison discovers fields from the run's metric snapshots, so it does
        // not depend on metric_score_results existing.
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "Case", 0.5);
        seedScore(runB, computationB, "Case", 0.5);
        assertThat(metricScoreResultRepository.findByRunAndComputation(runA, computationA))
                .isEmpty();

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        // All five per-metric statistics plus the default overall (one discovered field).
        assertThat(sideA.getScores()).hasSize(6);
        assertThat(score(sideA, "AVG", METRIC_FIELD)).isEqualTo(0.5);
        assertThat(score(sideA, "overall", "overall")).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("Should omit a declared output field that holds no numeric value")
    void shouldOmitNonNumericDeclaredOutputField() {
        // The schema declares a string field alongside the numeric one. Production never writes a string into
        // metric_values (MetricOutputFieldDto.value is a BigDecimal), so the field is simply absent from the
        // JSONB — its aggregate is NULL, which must be omitted rather than fail the numeric cast.
        final String mixedSchema =
                "{\"properties\":{\"score\":{\"type\":\"number\"},\"reason\":{\"type\":\"string\"}}}";
        analyticsTestDataHelper.createRunMetricSnapshot(runA, computationA, METRIC, mixedSchema, COMPUTED_AT_MS);
        analyticsTestDataHelper.createRunMetricSnapshot(runB, computationB, METRIC, mixedSchema, COMPUTED_AT_MS);
        seedScore(runA, computationA, "Case", 0.5);
        seedScore(runB, computationB, "Case", 0.5);

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        assertThat(score(sideA, "AVG", METRIC_FIELD)).isEqualTo(0.5);
        assertThat(sideA.getScores())
                .extracting(MetricScoreValueDto::getMetricName)
                .doesNotContain("Relevancy.reason");
        // Every surviving entry carries a value; nothing is reported as null.
        assertThat(sideA.getScores()).allSatisfy(s -> assertThat(s.getValue()).isNotNull());
    }

    @Test
    @DisplayName("Should divide a mean overall by every discovered field, including one with no values")
    void shouldDivideMeanByFullDiscoveredFieldCount() {
        // Two metrics are declared but only one carries data. The mean must still divide by 2, with the
        // empty metric's average coalesced to 0 — not silently become the surviving metric's average.
        metaTestDataHelper.setRunSuiteSnapshot(runA, snapshotWithMeanOverall());
        analyticsTestDataHelper.createRunMetricSnapshot(runA, computationA, METRIC, OUTPUT_SCHEMA, COMPUTED_AT_MS);
        analyticsTestDataHelper.createRunMetricSnapshot(runA, computationA, "Ghost", OUTPUT_SCHEMA, COMPUTED_AT_MS);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "Case", 0.5);
        seedScore(runB, computationB, "Case", 0.5);

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        // (0.5 + 0) / 2 — NOT 0.5, which is what dividing by the populated field count alone would give.
        assertThat(score(sideA, "overall", "overall")).isCloseTo(0.25, within(1e-9));
        // The empty metric contributes no per-metric entries of its own.
        assertThat(sideA.getScores())
                .extracting(MetricScoreValueDto::getMetricName)
                .doesNotContain("Ghost.score");
    }

    @Test
    @DisplayName("Should succeed with zero counts for a run that has no eval summary rows")
    void shouldSucceedWhenRunHasNoRows() {
        // Snapshots exist (so a computation resolves) but nothing was ever summarised.
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        assertThat(sideA.getTotalRowCount()).isZero();
        assertThat(sideA.getMatchedRowCount()).isZero();
        assertThat(sideA.getUnmatchedEvalSummaryIds()).isEmpty();
        assertThat(sideA.getScores()).isEmpty();
        assertThat(sideA.getAvgExecDurationMs()).isNull();
    }

    @Test
    @DisplayName("Should reject a comparison of a run against itself with 400")
    void shouldRejectSameRunTwice() {
        seedSnapshot(runA, computationA);

        final ResponseEntity<String> response = compareRaw(runA, runA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Named explicitly, because @Size(min=2,max=2) is satisfied by two identical ids — only the
        // distinctness guard rejects this, and a 400 alone would not tell the two apart.
        assertThat(response.getBody()).contains("distinct");
    }

    @Test
    @DisplayName("Should return 404 for an unknown run id")
    void shouldReturnNotFoundForUnknownRun() {
        seedSnapshot(runA, computationA);

        assertThat(compareRaw(runA, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should reject runs of different suites with 409")
    void shouldRejectRunsOfDifferentSuites() {
        final UUID otherSuiteId =
                metaTestDataHelper.createTestSuite("other-" + UUID.randomUUID()).getId();
        final UUID foreignRun =
                metaTestDataHelper.createTestSuiteRun(otherSuiteId).getId();
        seedSnapshot(runA, computationA);
        seedSnapshot(foreignRun, computationB);

        final ResponseEntity<String> response = compareRaw(runA, foreignRun);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_OPERATION");
    }

    @Test
    @DisplayName("Should reject a legacy run with no suite snapshot with 422")
    void shouldRejectRunWithoutSuiteSnapshot() {
        final UUID legacyRun =
                metaTestDataHelper.createLegacyTestSuiteRun(suiteId).getId();
        seedSnapshot(runA, computationA);
        seedSnapshot(legacyRun, computationB);

        final ResponseEntity<String> response = compareRaw(runA, legacyRun);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("SNAPSHOT_SUITE_MISSING");
    }

    @Test
    @DisplayName("Should reject a run with no metric computation with 409")
    void shouldRejectRunWithoutComputation() {
        // Run B has eval summaries but no run_metric_snapshots, so no computation is resolvable for it.
        seedSnapshot(runA, computationA);
        seedScore(runA, computationA, "Case", 0.5);
        seedScore(runB, computationB, "Case", 0.5);

        final ResponseEntity<String> response = compareRaw(runA, runB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_OPERATION").contains("no metric computation");
    }

    @Test
    @DisplayName("Should reject a comparison whose non-matching rows exceed the configured cap with 409")
    void shouldRejectWhenUnmatchedRowsExceedCap() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        // One past analytics.comparison.max-unmatched-rows (5000), none of which matches anything in B.
        analyticsTestDataHelper.createDistinctlyNamedEvalSummaries(
                suiteId, runA, computationA, "OnlyInA-", MAX_UNMATCHED_ROWS + 1, CREATED_AT_MS);
        seedScore(runB, computationB, "OnlyInB", 0.5);

        final ResponseEntity<String> response = compareRaw(runA, runB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The message names both the actual count and the limit, in exclusion terms.
        assertThat(response.getBody())
                .contains("INVALID_OPERATION")
                .contains(String.valueOf(MAX_UNMATCHED_ROWS + 1))
                .contains("analytics.comparison.max-unmatched-rows");
    }

    @Test
    @DisplayName("Should compare a cancelled run, which is not gated on how the run ended")
    void shouldCompareCancelledRun() {
        final UUID cancelledRun = metaTestDataHelper
                .createTestSuiteRun(suiteId, RunStatus.CANCELLED)
                .getId();
        seedSnapshot(runA, computationA);
        seedSnapshot(cancelledRun, computationB);
        seedScore(runA, computationA, "Case", 0.5);
        seedScore(cancelledRun, computationB, "Case", 0.5);

        final RunComparisonResponseDto response = compare(runA, cancelledRun);

        assertThat(response.getRuns().get(1).getMatchedRowCount()).isEqualTo(1L);
        assertThat(response.getRuns().get(1).getScores()).isNotEmpty();
    }

    @Test
    @DisplayName("Should count a failed matched row as matched but not as successful")
    void shouldMatchFailedRowOutsideSuccessCount() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "Ok", 0.5);
        analyticsTestDataHelper.createEvalSummary(fixture(runA, computationA, "Broken", 0.5)
                .executionStatus(ExecutionStatus.FAILED.name())
                .build());
        seedScore(runB, computationB, "Ok", 0.5);
        seedScore(runB, computationB, "Broken", 0.5);

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        // Execution status is not part of the match key, so the failed row matches...
        assertThat(sideA.getMatchedRowCount()).isEqualTo(2L);
        // ...but the success ratio the FE renders is 1 of 2.
        assertThat(sideA.getMatchedSuccessRowCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should average execution duration over the matched rows only")
    void shouldAverageExecutionDurationOverMatchedRowsOnly() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        analyticsTestDataHelper.createEvalSummary(
                fixture(runA, computationA, "Fast", 0.5).execDurationMs(100L).build());
        analyticsTestDataHelper.createEvalSummary(
                fixture(runA, computationA, "Slow", 0.5).execDurationMs(300L).build());
        // Present only in A, and slow enough that including it would be obvious.
        analyticsTestDataHelper.createEvalSummary(fixture(runA, computationA, "OnlyInA", 0.5)
                .execDurationMs(9000L)
                .build());
        seedScore(runB, computationB, "Fast", 0.5);
        seedScore(runB, computationB, "Slow", 0.5);

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        // Exactly (100 + 300) / 2. A run-wide average would be 3133.33…, so this pins the population.
        assertThat(sideA.getAvgExecDurationMs()).isEqualTo(200.0);
        assertThat(sideA.getMatchedRowCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should include a non-successful matched row in the duration average")
    void shouldIncludeNonSuccessRowInDurationAverage() {
        // The chosen population is ALL matched rows, so a synthetic ERROR row's fabricated 0 duration pulls
        // the mean down. Asserted as behaviour rather than left as prose, since it is the accepted cost of a
        // denominator the FE can verify from the response.
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        analyticsTestDataHelper.createEvalSummary(
                fixture(runA, computationA, "Ok", 0.5).execDurationMs(100L).build());
        analyticsTestDataHelper.createEvalSummary(fixture(runA, computationA, "Crashed", 0.5)
                .executionStatus(ExecutionStatus.ERROR.name())
                .execDurationMs(0L)
                .build());
        seedScore(runB, computationB, "Ok", 0.5);
        seedScore(runB, computationB, "Crashed", 0.5);

        final RunComparisonRunDto sideA = compare(runA, runB).getRuns().get(0);

        // (100 + 0) / 2 — halved by the errored row, not 100.
        assertThat(sideA.getAvgExecDurationMs()).isEqualTo(50.0);
        assertThat(sideA.getMatchedSuccessRowCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should omit avgExecDurationMs from the response body when nothing matched")
    void shouldOmitAvgExecDurationFromBodyWhenNothingMatches() {
        seedSnapshot(runA, computationA);
        seedSnapshot(runB, computationB);
        seedScore(runA, computationA, "OnlyInA", 0.5);
        seedScore(runB, computationB, "OnlyInB", 0.5);

        final ResponseEntity<String> response = compareRaw(runA, runB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Asserted on the serialized body, not the DTO: what is being verified is that the global NON_NULL
        // inclusion drops the key entirely, so a client sees an absent field rather than 0 or null.
        assertThat(response.getBody()).isNotNull().doesNotContain("avgExecDurationMs");
        // The rest of the payload is still there, so the absence above is not an empty response.
        assertThat(response.getBody()).contains("matchedRowCount").contains(runA.toString());
    }

    private ResponseEntity<String> compareRaw(UUID first, UUID second) {
        return restTemplate.getForEntity(
                apiUrl("/analytics/metric-scores/comparison?runIds={first},{second}"), String.class, first, second);
    }

    /** The v2 snapshot {@code createTestSuiteRun} writes, plus a {@code mean} overall definition. */
    private String snapshotWithMeanOverall() {
        return "{\"snapshotVersion\":\"2\",\"datasetRef\":{\"id\":\"" + UUID.randomUUID()
                + "\",\"version\":1,\"name\":\"ds\"},\"testCaseSchema\":[],\"responseColumns\":[],"
                + "\"overallScore\":{\"type\":\"mean\"}}";
    }

    private RunComparisonResponseDto compare(UUID first, UUID second) {
        final ResponseEntity<RunComparisonResponseDto> response = restTemplate.getForEntity(
                apiUrl("/analytics/metric-scores/comparison?runIds={first},{second}"),
                RunComparisonResponseDto.class,
                first,
                second);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRuns()).hasSize(2);
        // Checked here rather than per test so it holds on every fixture in this class: the per-side anti-join
        // partitions each run's rows, so matched and excluded must account for the whole population.
        assertThat(response.getBody().getRuns())
                .allSatisfy(run -> assertThat(run.getMatchedRowCount()
                                + run.getUnmatchedEvalSummaryIds().size())
                        .as("matched + unmatched must equal total for run %s", run.getRunId())
                        .isEqualTo(run.getTotalRowCount()));
        return response.getBody();
    }

    private void seedSnapshot(UUID runId, UUID computationId) {
        analyticsTestDataHelper.createRunMetricSnapshot(runId, computationId, METRIC, OUTPUT_SCHEMA, COMPUTED_AT_MS);
    }

    private void seedScore(UUID runId, UUID computationId, String testCaseName, double score) {
        analyticsTestDataHelper.createEvalSummary(
                fixture(runId, computationId, testCaseName, score).build());
    }

    /** Same case name at an explicit repetition index, so {@code run_index} participates in the key. */
    private void seedRepetition(UUID runId, UUID computationId, int runIndex, double score) {
        analyticsTestDataHelper.createEvalSummary(fixture(runId, computationId, "Repeated", score)
                .runIndex(runIndex)
                .build());
    }

    /** One turn of a multi-turn conversation, all turns sharing the case name. */
    private void seedTurn(
            UUID runId, UUID computationId, int turnIndex, int totalTurns, double score, long execDurationMs) {
        analyticsTestDataHelper.createEvalSummary(fixture(runId, computationId, "Conversation", score)
                .turnIndex(turnIndex)
                .totalTurns(totalTurns)
                .execDurationMs(execDurationMs)
                .build());
    }

    private EvalSummaryFixture.EvalSummaryFixtureBuilder fixture(
            UUID runId, UUID computationId, String testCaseName, double score) {
        return EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName(testCaseName)
                .executionStatus(ExecutionStatus.SUCCESS.name())
                .createdAtMs(CREATED_AT_MS)
                .metricValuesJson("{\"" + METRIC + "\":{\"score\":" + score + "}}");
    }

    private void computePhaseThree(UUID runId, UUID computationId) {
        phaseThreeExecutor.execute(MetricScoreComputationContext.builder()
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(computationId)
                .computedAtMs(COMPUTED_AT_MS)
                .cancellationSignal(new AtomicBoolean(false))
                .build());
    }

    private static List<String> triples(RunComparisonRunDto run) {
        return run.getScores().stream()
                .map(s -> s.getMetricScoreName() + "|" + s.getMetricName() + "|" + s.getValue())
                .sorted()
                .toList();
    }

    private static List<String> persistedTriples(List<MetricScoreResult> results) {
        return results.stream()
                .map(r -> r.getMetricScoreName() + "|" + r.getMetricName() + "|" + r.getValue())
                .sorted()
                .toList();
    }

    private static double score(RunComparisonRunDto run, String statistic, String metricName) {
        return run.getScores().stream()
                .filter(s -> statistic.equals(s.getMetricScoreName()) && metricName.equals(s.getMetricName()))
                .map(MetricScoreValueDto::getValue)
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("missing score " + statistic + "/" + metricName + " in " + triples(run)));
    }
}
