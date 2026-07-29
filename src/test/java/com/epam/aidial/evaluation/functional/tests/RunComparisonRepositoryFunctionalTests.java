package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryMatchStats;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Repository-level tests for the per-side anti-join backing the run-comparison endpoint.
 *
 * <p>The load-bearing case is duplicate match keys: a design that collapsed them (e.g. via
 * {@code DISTINCT ON}) would drop a real row from the aggregate population, so the fixtures deliberately
 * give duplicate rows <strong>differing durations</strong> — making a collapse numerically detectable in the
 * average rather than only visible in a count.
 */
@DisplayName("Run Comparison Repository Functional Tests")
public abstract class RunComparisonRepositoryFunctionalTests extends BaseFunctionalTest {

    private static final long CREATED_AT_MS = 1_700_000_000_000L;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    private UUID suiteId;
    private UUID runA;
    private UUID runB;
    private UUID computationA;
    private UUID computationB;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        suiteId = UUID.randomUUID();
        runA = UUID.randomUUID();
        runB = UUID.randomUUID();
        computationA = UUID.randomUUID();
        computationB = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should match every row sharing a duplicated key and average all of them")
    void shouldMatchAllRowsSharingDuplicateKey() {
        // Side A holds two rows for one key (as the eval-results import path can produce) plus one row
        // with no counterpart; side B holds a single row for the shared key.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Shared", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Shared", ExecutionStatus.SUCCESS.name(), 300L, CREATED_AT_MS);
        UUID onlyInA = analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "OnlyInA", ExecutionStatus.SUCCESS.name(), 9000L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Shared", ExecutionStatus.SUCCESS.name(), 50L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);
        EvalSummaryMatchStats statsB = evalSummaryRepository.countMatches(runB, computationB, runA, computationA);

        // Both duplicate rows match — neither is dropped in favour of the other.
        assertThat(statsA.totalRows()).isEqualTo(3L);
        assertThat(statsA.matchedRows()).isEqualTo(2L);
        assertThat(statsA.matchedSuccessRows()).isEqualTo(2L);

        // A legitimately matches more rows than B; no cross-run count equality is guaranteed.
        assertThat(statsB.totalRows()).isEqualTo(1L);
        assertThat(statsB.matchedRows()).isEqualTo(1L);
        assertThat(statsA.matchedRows()).isGreaterThan(statsB.matchedRows());

        // Mean of BOTH duplicate rows, scoped to the matched set: (100 + 300) / 2.
        // A collapse would yield 100 or 300; including the unmatched row would yield 3133.33.
        assertThat(statsA.avgExecDurationMs()).isEqualByComparingTo(BigDecimal.valueOf(200));

        assertThat(evalSummaryRepository.findUnmatchedIds(runA, computationA, runB, computationB))
                .containsExactly(onlyInA);
        assertThat(evalSummaryRepository.findUnmatchedIds(runB, computationB, runA, computationA))
                .isEmpty();
    }

    @Test
    @DisplayName("Should report each run's success count against the shared matched denominator")
    void shouldReportPerRunSuccessRatiosOverMatchedRows() {
        // The comparison view renders "N of M" per run, where M is the matched count. Both runs cover the
        // same three test cases but succeeded on a different number of them. A non-SUCCESS row here stands
        // for either a failed call or a run where one metric errored — the stored status cannot distinguish
        // the two, which is exactly why this count is not a statistic's denominator.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Case1", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Case2", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Case3", ExecutionStatus.FAILED.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Case1", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Case2", ExecutionStatus.FAILED.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Case3", ExecutionStatus.ERROR.name(), 100L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);
        EvalSummaryMatchStats statsB = evalSummaryRepository.countMatches(runB, computationB, runA, computationA);

        // "2 of 3" beside "1 of 3" — the denominator is shared, the numerators are per-run.
        assertThat(statsA.matchedRows()).isEqualTo(3L);
        assertThat(statsA.matchedSuccessRows()).isEqualTo(2L);
        assertThat(statsB.matchedRows()).isEqualTo(3L);
        assertThat(statsB.matchedSuccessRows()).isEqualTo(1L);
        // Every status other than SUCCESS is excluded from the numerator, not just FAILED.
        assertThat(statsB.matchedSuccessRows()).isLessThan(statsB.matchedRows());
    }

    @Test
    @DisplayName("Should match test case names case-insensitively")
    void shouldMatchNamesCaseInsensitively() {
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Foo", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "foo", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);

        assertThat(statsA.matchedRows()).isEqualTo(1L);
        assertThat(evalSummaryRepository.findUnmatchedIds(runA, computationA, runB, computationB))
                .isEmpty();
    }

    @Test
    @DisplayName("Should include a failed matched row in the average but not in the success count")
    void shouldIncludeFailedRowInAverage() {
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Ok", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Broken", ExecutionStatus.FAILED.name(), 300L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Ok", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Broken", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);

        // Execution status is not part of the match key, so the failed row still matches...
        assertThat(statsA.matchedRows()).isEqualTo(2L);
        // ...but is excluded from the success count...
        assertThat(statsA.matchedSuccessRows()).isEqualTo(1L);
        // ...and is included in the average, whose denominator is matchedRows.
        assertThat(statsA.avgExecDurationMs()).isEqualByComparingTo(BigDecimal.valueOf(200));
    }

    @Test
    @DisplayName("Should return a null average and all ids when nothing matches")
    void shouldReturnNullAverageWhenNothingMatches() {
        UUID firstA = analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "AaaOnlyInA", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        UUID secondA = analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "BbbAlsoOnlyInA", ExecutionStatus.SUCCESS.name(), 300L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "OnlyInB", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);

        assertThat(statsA.totalRows()).isEqualTo(2L);
        assertThat(statsA.matchedRows()).isZero();
        assertThat(statsA.matchedSuccessRows()).isZero();
        // avg over an empty set is NULL, and the column is NOT NULL, so null cannot mean anything else.
        assertThat(statsA.avgExecDurationMs()).isNull();

        assertThat(evalSummaryRepository.findUnmatchedIds(runA, computationA, runB, computationB))
                .containsExactly(firstA, secondA);
    }

    @Test
    @DisplayName("Should order unmatched ids identically across identical calls")
    void shouldOrderUnmatchedIdsDeterministically() {
        for (int i = 0; i < 8; i++) {
            analyticsTestDataHelper.createEvalSummary(
                    suiteId, runA, computationA, "Case", ExecutionStatus.SUCCESS.name(), 100L + i, CREATED_AT_MS);
        }
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Unrelated", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);

        List<UUID> first = evalSummaryRepository.findUnmatchedIds(runA, computationA, runB, computationB);
        List<UUID> second = evalSummaryRepository.findUnmatchedIds(runA, computationA, runB, computationB);

        assertThat(first).hasSize(8);
        assertThat(first).containsExactlyElementsOf(second);
        // Repeating a query is not by itself proof of an ORDER BY — an unordered scan can return the same
        // order twice by chance. Every row here shares name, run index and turn index, so the ordering
        // reduces to the id tiebreaker, which is assertable. Note the column is VARCHAR(36), so the sort is
        // lexicographic on the textual form, which is NOT the same as UUID.compareTo's signed-long order.
        assertThat(first.stream().map(UUID::toString).toList()).isSorted();
    }

    @Test
    @DisplayName("Should scope matching to the requested computation")
    void shouldScopeMatchingToRequestedComputation() {
        UUID staleComputation = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Shared", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        // Same run, same name, but an earlier computation — a re-evaluation mints a new computation id and
        // both generations coexist in the table.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, staleComputation, "Shared", ExecutionStatus.SUCCESS.name(), 9000L, CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, computationB, "Shared", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);

        // The stale generation contributes to neither the population nor the average.
        assertThat(statsA.totalRows()).isEqualTo(1L);
        assertThat(statsA.matchedRows()).isEqualTo(1L);
        assertThat(statsA.avgExecDurationMs()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("Should not match a row whose counterpart exists only under another computation")
    void shouldNotMatchAcrossOtherRunComputations() {
        UUID staleComputationB = UUID.randomUUID();
        UUID onlyUnderCurrentComputation = analyticsTestDataHelper.createEvalSummary(
                suiteId, runA, computationA, "Shared", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);
        // Side B carries the key only under a computation the caller did not ask about.
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runB, staleComputationB, "Shared", ExecutionStatus.SUCCESS.name(), 100L, CREATED_AT_MS);

        EvalSummaryMatchStats statsA = evalSummaryRepository.countMatches(runA, computationA, runB, computationB);

        assertThat(statsA.totalRows()).isEqualTo(1L);
        assertThat(statsA.matchedRows()).isZero();
        assertThat(statsA.avgExecDurationMs()).isNull();
        assertThat(evalSummaryRepository.findUnmatchedIds(runA, computationA, runB, computationB))
                .containsExactly(onlyUnderCurrentComputation);
    }
}
