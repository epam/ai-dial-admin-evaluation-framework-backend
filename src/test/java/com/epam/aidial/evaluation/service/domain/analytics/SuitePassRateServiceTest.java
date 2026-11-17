package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("SuitePassRateService")
class SuitePassRateServiceTest {

    private EvalSummaryRepository evalSummaryRepository;
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;
    private TestSuiteRunService testSuiteRunService;
    private SuitePassRateService service;

    @BeforeEach
    void setUp() {
        evalSummaryRepository = mock(EvalSummaryRepository.class);
        testCaseEvalScoreRepository = mock(TestCaseEvalScoreRepository.class);
        testSuiteRunService = mock(TestSuiteRunService.class);
        service = new SuitePassRateService(
                evalSummaryRepository,
                testCaseEvalScoreRepository,
                testSuiteRunService,
                new PassRateCalculator(),
                directTransactionManager());
    }

    /** Stubs the completed-run selection, the analytics reads and the threshold read for one scenario. */
    private void stub(
            Map<UUID, List<UUID>> completedRuns, Map<UUID, RunPassCounts> counts, Map<UUID, Double> thresholds) {
        Map<UUID, UUID> computations = new HashMap<>();
        completedRuns.values().stream().flatMap(List::stream).forEach(run -> computations.put(run, UUID.randomUUID()));
        stub(completedRuns, computations, counts, thresholds);
    }

    private void stub(
            Map<UUID, List<UUID>> completedRuns,
            Map<UUID, UUID> computations,
            Map<UUID, RunPassCounts> counts,
            Map<UUID, Double> thresholds) {
        when(testSuiteRunService.getLatestCompletedRunIdsBySuite(anyCollection(), eq(2)))
                .thenReturn(completedRuns);
        when(evalSummaryRepository.findLatestComputationIds(anyCollection())).thenReturn(computations);
        when(testCaseEvalScoreRepository.countByRunAndComputation(anyMap())).thenReturn(counts);
        when(testSuiteRunService.getThresholdsByRunIds(anyCollection())).thenReturn(thresholds);
    }

    private static RunPassCounts counts(UUID runId, long passedTrue, long passedFalse, long unscored, long failed) {
        return new RunPassCounts(runId, passedTrue, passedFalse, unscored, failed);
    }

    private static Map<UUID, RunPassCounts> byRun(RunPassCounts... counts) {
        Map<UUID, RunPassCounts> map = new HashMap<>();
        for (RunPassCounts c : counts) {
            map.put(c.runId(), c);
        }
        return map;
    }

    @Test
    @DisplayName("two suites, one with a previous run: latest mean 0.75 over 2, previous mean 0.0 over 1")
    void twoSuitesOneWithPreviousRun() {
        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        UUID firstSuiteLatest = UUID.randomUUID();
        UUID firstSuitePrevious = UUID.randomUUID();
        UUID secondSuiteLatest = UUID.randomUUID();
        stub(
                Map.of(suiteA, List.of(firstSuiteLatest, firstSuitePrevious), suiteB, List.of(secondSuiteLatest)),
                byRun(
                        counts(firstSuiteLatest, 1, 0, 0, 1),
                        counts(firstSuitePrevious, 0, 0, 0, 2),
                        counts(secondSuiteLatest, 0, 0, 2, 0)),
                Map.of(firstSuiteLatest, 0.5, firstSuitePrevious, 0.5));

        SuitePassRateSummaryDto summary = service.summarize(List.of(suiteA, suiteB));

        assertThat(summary.getPassRate()).isEqualTo(0.75);
        assertThat(summary.getSuiteCount()).isEqualTo(2);
        assertThat(summary.getPreviousPassRate()).isEqualTo(0.0);
        assertThat(summary.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("no completed runs: both rates are null, counts are 0 and neither analytics nor thresholds are read")
    void noCompletedRuns() {
        when(testSuiteRunService.getLatestCompletedRunIdsBySuite(anyCollection(), eq(2)))
                .thenReturn(Map.of());

        SuitePassRateSummaryDto summary = service.summarize(List.of(UUID.randomUUID()));

        assertThat(summary.getPassRate()).isNull();
        assertThat(summary.getPreviousPassRate()).isNull();
        assertThat(summary.getSuiteCount()).isZero();
        assertThat(summary.getPreviousSuiteCount()).isZero();
        verify(evalSummaryRepository, never()).findLatestComputationIds(anyCollection());
        verify(testCaseEvalScoreRepository, never()).countByRunAndComputation(anyMap());
        verify(testSuiteRunService, never()).getThresholdsByRunIds(anyCollection());
    }

    @Test
    @DisplayName("completed runs without any score rows: both rates are null and no thresholds are read")
    void completedRunsWithoutScores() {
        UUID suite = UUID.randomUUID();
        stub(Map.of(suite, List.of(UUID.randomUUID())), Map.of(), Map.of());

        SuitePassRateSummaryDto summary = service.summarize(List.of(suite));

        assertThat(summary.getPassRate()).isNull();
        assertThat(summary.getSuiteCount()).isZero();
        verify(testSuiteRunService, never()).getThresholdsByRunIds(anyCollection());
    }

    @Test
    @DisplayName(
            "a run with a threshold counts passed and unscored test cases: 1 passed, 1 below, 1 unscored, 1 failed is"
                    + " 0.5")
    void thresholdRunCountsPassedAndUnscored() {
        UUID suite = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        stub(Map.of(suite, List.of(run)), byRun(counts(run, 1, 1, 1, 1)), Map.of(run, 0.5));

        assertThat(service.summarize(List.of(suite)).getPassRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a run without a threshold counts every SUCCESS test case: 2 SUCCESS and 1 failed is 2/3")
    void noThresholdRunCountsSuccessRows() {
        UUID suite = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        stub(Map.of(suite, List.of(run)), byRun(counts(run, 0, 0, 2, 1)), Map.of());

        assertThat(service.summarize(List.of(suite)).getPassRate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("each suite counts once: a 100-test-case suite at 1.0 and a 2-test-case suite at 0.0 average 0.5")
    void eachSuiteCountsOnce() {
        UUID big = UUID.randomUUID();
        UUID small = UUID.randomUUID();
        UUID bigRun = UUID.randomUUID();
        UUID smallRun = UUID.randomUUID();
        stub(
                Map.of(big, List.of(bigRun), small, List.of(smallRun)),
                byRun(counts(bigRun, 100, 0, 0, 0), counts(smallRun, 0, 0, 0, 2)),
                Map.of(bigRun, 0.5, smallRun, 0.5));

        assertThat(service.summarize(List.of(big, small)).getPassRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a latest completed run with no score rows contributes nothing and is not replaced by an older run")
    void latestRunWithoutCountsContributesNothing() {
        UUID suite = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        stub(Map.of(suite, List.of(newest, older)), byRun(counts(older, 2, 0, 0, 0)), Map.of(older, 0.5));

        SuitePassRateSummaryDto summary = service.summarize(List.of(suite));

        assertThat(summary.getPassRate()).isNull();
        assertThat(summary.getSuiteCount()).isZero();
        assertThat(summary.getPreviousPassRate()).isEqualTo(1.0);
        assertThat(summary.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("each run is counted against the latest computation the analytics read returned for it")
    void countsAgainstLatestComputations() {
        UUID suite = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newestComputation = UUID.randomUUID();
        UUID olderComputation = UUID.randomUUID();
        stub(
                Map.of(suite, List.of(newest, older)),
                Map.of(newest, newestComputation, older, olderComputation),
                byRun(counts(newest, 1, 0, 0, 0), counts(older, 1, 0, 0, 0)),
                Map.of());

        service.summarize(List.of(suite));

        verify(testCaseEvalScoreRepository)
                .countByRunAndComputation(Map.of(newest, newestComputation, older, olderComputation));
    }

    @Test
    @DisplayName("duplicate suite ids are removed before the run selection")
    void duplicateIdsAreRemoved() {
        UUID suite = UUID.randomUUID();
        when(testSuiteRunService.getLatestCompletedRunIdsBySuite(anyCollection(), eq(2)))
                .thenReturn(Map.of());

        service.summarize(List.of(suite, suite, suite));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(testSuiteRunService).getLatestCompletedRunIdsBySuite(captor.capture(), eq(2));
        assertThat(captor.getValue()).containsExactly(suite);
    }

    @Test
    @DisplayName("null suiteIds is rejected with a ValidationException")
    void nullIdsRejected() {
        assertThatThrownBy(() -> service.summarize(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("empty suiteIds is rejected with a ValidationException")
    void emptyIdsRejected() {
        assertThatThrownBy(() -> service.summarize(List.of())).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a null entry in suiteIds is rejected with a ValidationException before any read")
    void nullEntryRejected() {
        List<UUID> ids = new ArrayList<>();
        ids.add(UUID.randomUUID());
        ids.add(null);

        assertThatThrownBy(() -> service.summarize(ids)).isInstanceOf(ValidationException.class);
        verify(testSuiteRunService, never()).getLatestCompletedRunIdsBySuite(anyCollection(), any(Integer.class));
    }

    @Test
    @DisplayName("more distinct suite ids than the cap is rejected with a ValidationException")
    void overCapRejected() {
        List<UUID> ids = IntStream.rangeClosed(0, ValidationConstants.MAX_BATCH_SUITE_IDS)
                .mapToObj(i -> UUID.randomUUID())
                .collect(Collectors.toCollection(ArrayList::new));

        assertThatThrownBy(() -> service.summarize(ids))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(String.valueOf(ValidationConstants.MAX_BATCH_SUITE_IDS));
        verify(testSuiteRunService, never()).getLatestCompletedRunIdsBySuite(anyCollection(), any(Integer.class));
    }

    /** Runs the callback inline, as a real read-only transaction would, without a database. */
    private static PlatformTransactionManager directTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
