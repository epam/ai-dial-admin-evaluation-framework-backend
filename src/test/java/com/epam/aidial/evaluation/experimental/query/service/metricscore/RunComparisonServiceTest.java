package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.RunComparisonProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryMatchStats;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreValueDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.Mean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("RunComparisonService")
class RunComparisonServiceTest {

    private static final UUID RUN_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUITE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_SUITE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID COMPUTATION_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID COMPUTATION_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID UNMATCHED_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final int MAX_UNMATCHED = 10;

    private final TestSuiteRunService testSuiteRunService = mock(TestSuiteRunService.class);
    private final ComputationResolver computationResolver = mock(ComputationResolver.class);
    private final EvalSummaryRepository evalSummaryRepository = mock(EvalSummaryRepository.class);
    private final RunMetricSnapshotRepository snapshotRepository = mock(RunMetricSnapshotRepository.class);
    private final MetricFieldDiscoverer metricFieldDiscoverer = mock(MetricFieldDiscoverer.class);
    private final FilteredMetricScoreAggregator aggregator = mock(FilteredMetricScoreAggregator.class);

    private RunComparisonService service;

    @BeforeEach
    void setUp() {
        RunComparisonProperties properties = new RunComparisonProperties();
        properties.setMaxUnmatchedRows(MAX_UNMATCHED);
        service = new RunComparisonService(
                testSuiteRunService,
                computationResolver,
                evalSummaryRepository,
                snapshotRepository,
                metricFieldDiscoverer,
                aggregator,
                properties,
                directTransactionManager());
    }

    // ----- guards, in the order the service applies them -----

    @Test
    @DisplayName("Should reject a request that does not name exactly two runs")
    void shouldRejectWrongRunCount() {
        assertThatThrownBy(() -> service.compare(List.of(RUN_A))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B, SUITE_ID)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.compare(null)).isInstanceOf(ValidationException.class);

        verify(testSuiteRunService, never()).getRun(any());
    }

    @Test
    @DisplayName("Should reject the same run named twice")
    void shouldRejectSameRunTwice() {
        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_A)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    @DisplayName("Should propagate not-found for an unknown run")
    void shouldPropagateUnknownRun() {
        when(testSuiteRunService.getRun(RUN_A)).thenThrow(new EntityNotFoundException("nope"));

        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B))).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("Should reject runs belonging to different suites")
    void shouldRejectDifferentSuites() {
        when(testSuiteRunService.getRun(RUN_A)).thenReturn(run(RUN_A, SUITE_ID, snapshot(null)));
        when(testSuiteRunService.getRun(RUN_B)).thenReturn(run(RUN_B, OTHER_SUITE_ID, snapshot(null)));

        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B)))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("different test suites");
    }

    @Test
    @DisplayName("Should reject a run without a suite snapshot")
    void shouldRejectMissingSnapshot() {
        when(testSuiteRunService.getRun(RUN_A)).thenReturn(run(RUN_A, SUITE_ID, null));
        when(testSuiteRunService.getRun(RUN_B)).thenReturn(run(RUN_B, SUITE_ID, snapshot(null)));

        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B)))
                .isInstanceOf(SnapshotSuiteMissingException.class);
    }

    @Test
    @DisplayName("Should prefer the different-suite failure over a missing snapshot on the other run")
    void shouldApplySuiteGuardBeforeSnapshotGuard() {
        // Guard order is observable: run B is legacy AND from another suite, and the suite mismatch wins.
        when(testSuiteRunService.getRun(RUN_A)).thenReturn(run(RUN_A, SUITE_ID, snapshot(null)));
        when(testSuiteRunService.getRun(RUN_B)).thenReturn(run(RUN_B, OTHER_SUITE_ID, null));

        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B))).isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("Should reject a run with no resolvable computation")
    void shouldRejectRunWithoutComputation() {
        stubRuns(null, null);
        when(computationResolver.resolve(null, RUN_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B)))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("no metric computation");
    }

    // ----- counts, then cap, then ids -----

    @Test
    @DisplayName("Should reject over the cap before fetching a single id")
    void shouldEnforceCapBeforeFetchingIds() {
        stubRuns(null, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(MAX_UNMATCHED + 5L, 0L, 0L, null));

        assertThatThrownBy(() -> service.compare(List.of(RUN_A, RUN_B)))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining(String.valueOf(MAX_UNMATCHED + 5L))
                .hasMessageContaining(String.valueOf(MAX_UNMATCHED))
                .hasMessageContaining("analytics.comparison.max-unmatched-rows");

        // The whole point of counts-before-ids: no id ever leaves the database when the cap trips.
        verify(evalSummaryRepository, never()).findUnmatchedIds(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should accept a comparison whose unmatched count is exactly at the cap")
    void shouldAcceptUnmatchedCountAtCap() {
        stubRuns(null, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(MAX_UNMATCHED + 1L, 1L, 1L, BigDecimal.ONE));
        stubStats(RUN_B, new EvalSummaryMatchStats(1L, 1L, 1L, BigDecimal.ONE));
        stubUnmatched();

        assertThat(service.compare(List.of(RUN_A, RUN_B)).getRuns()).hasSize(2);
    }

    // ----- response assembly -----

    @Test
    @DisplayName("Should report runs in request order with each run's own counts")
    void shouldReportRunsInRequestOrder() {
        stubRuns(null, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(3L, 2L, 1L, BigDecimal.valueOf(200)));
        stubStats(RUN_B, new EvalSummaryMatchStats(5L, 1L, 1L, BigDecimal.valueOf(50)));
        when(evalSummaryRepository.findUnmatchedIds(RUN_A, COMPUTATION_A, RUN_B, COMPUTATION_B))
                .thenReturn(List.of(UNMATCHED_ID));
        when(evalSummaryRepository.findUnmatchedIds(RUN_B, COMPUTATION_B, RUN_A, COMPUTATION_A))
                .thenReturn(List.of());
        when(aggregator.aggregate(any())).thenReturn(List.of(score()));

        // Requested B first, so response order must follow the request, not the argument order internally.
        RunComparisonResponseDto response = service.compare(List.of(RUN_B, RUN_A));

        assertThat(response.getRuns()).extracting(r -> r.getRunId()).containsExactly(RUN_B, RUN_A);
        assertThat(response.getRuns().get(1)).satisfies(a -> {
            assertThat(a.getComputationId()).isEqualTo(COMPUTATION_A);
            assertThat(a.getTotalRowCount()).isEqualTo(3L);
            assertThat(a.getMatchedRowCount()).isEqualTo(2L);
            assertThat(a.getMatchedSuccessRowCount()).isEqualTo(1L);
            assertThat(a.getAvgExecDurationMs()).isEqualTo(200.0);
            assertThat(a.getUnmatchedEvalSummaryIds()).containsExactly(UNMATCHED_ID);
        });
        // Asymmetric matched counts are accepted, not rejected: a run holding duplicate keys matches more.
        assertThat(response.getRuns().get(0).getMatchedRowCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should report a null average duration when a run matched nothing")
    void shouldReportNullAverageWhenNothingMatched() {
        stubRuns(null, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(1L, 0L, 0L, null));
        stubStats(RUN_B, new EvalSummaryMatchStats(1L, 0L, 0L, null));
        stubUnmatched();

        assertThat(service.compare(List.of(RUN_A, RUN_B)).getRuns())
                .allSatisfy(r -> assertThat(r.getAvgExecDurationMs()).isNull());
    }

    @Test
    @DisplayName("Should issue no aggregate query when a run matched nothing")
    void shouldShortCircuitWhenNothingMatched() {
        stubRuns(null, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(4L, 0L, 0L, null));
        stubStats(RUN_B, new EvalSummaryMatchStats(4L, 0L, 0L, null));
        stubUnmatched();

        RunComparisonResponseDto response = service.compare(List.of(RUN_A, RUN_B));

        assertThat(response.getRuns()).allSatisfy(r -> assertThat(r.getScores()).isEmpty());
        verify(aggregator, never()).aggregate(any());
    }

    @Test
    @DisplayName("Should pass each run's own definition, computation and exclusion list to the aggregator")
    void shouldPassPerRunInputsToAggregator() {
        OverallScoreDefinition definitionA = new Mean();
        stubRuns(definitionA, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(2L, 1L, 1L, BigDecimal.ONE));
        stubStats(RUN_B, new EvalSummaryMatchStats(2L, 1L, 1L, BigDecimal.ONE));
        when(evalSummaryRepository.findUnmatchedIds(RUN_A, COMPUTATION_A, RUN_B, COMPUTATION_B))
                .thenReturn(List.of(UNMATCHED_ID));
        when(evalSummaryRepository.findUnmatchedIds(RUN_B, COMPUTATION_B, RUN_A, COMPUTATION_A))
                .thenReturn(List.of());
        when(metricFieldDiscoverer.discover(any())).thenReturn(List.of(new MetricField("metric::A::score", "A.score")));
        when(aggregator.aggregate(any())).thenReturn(List.of(score()));

        service.compare(List.of(RUN_A, RUN_B));

        ArgumentCaptor<FilteredMetricScoreRequest> captor = ArgumentCaptor.forClass(FilteredMetricScoreRequest.class);
        verify(aggregator, times(2)).aggregate(captor.capture());
        assertThat(captor.getAllValues().get(0)).satisfies(request -> {
            assertThat(request.runId()).isEqualTo(RUN_A);
            assertThat(request.computationId()).isEqualTo(COMPUTATION_A);
            assertThat(request.unmatchedEvalSummaryIds()).containsExactly(UNMATCHED_ID);
            assertThat(request.overallScoreDefinition()).isSameAs(definitionA);
        });
        // Run B's definition is its own — null here — never run A's.
        assertThat(captor.getAllValues().get(1).overallScoreDefinition()).isNull();
        assertThat(captor.getAllValues().get(1).unmatchedEvalSummaryIds()).isEmpty();
    }

    @Test
    @DisplayName("Should resolve each run's computation once and reuse it for matching and aggregation")
    void shouldResolveComputationOncePerRun() {
        stubRuns(null, null);
        stubComputations();
        stubStats(RUN_A, new EvalSummaryMatchStats(1L, 1L, 1L, BigDecimal.ONE));
        stubStats(RUN_B, new EvalSummaryMatchStats(1L, 1L, 1L, BigDecimal.ONE));
        stubUnmatched();
        when(aggregator.aggregate(any())).thenReturn(List.of());

        service.compare(List.of(RUN_A, RUN_B));

        verify(computationResolver, times(1)).resolve(null, RUN_A);
        verify(computationResolver, times(1)).resolve(null, RUN_B);
        // The same computation is used for both directions of the match query.
        verify(evalSummaryRepository).countMatches(RUN_A, COMPUTATION_A, RUN_B, COMPUTATION_B);
        verify(evalSummaryRepository).countMatches(RUN_B, COMPUTATION_B, RUN_A, COMPUTATION_A);
    }

    // ----- helpers -----

    private void stubRuns(OverallScoreDefinition definitionA, OverallScoreDefinition definitionB) {
        when(testSuiteRunService.getRun(RUN_A)).thenReturn(run(RUN_A, SUITE_ID, snapshot(definitionA)));
        when(testSuiteRunService.getRun(RUN_B)).thenReturn(run(RUN_B, SUITE_ID, snapshot(definitionB)));
    }

    private void stubComputations() {
        when(computationResolver.resolve(null, RUN_A)).thenReturn(Optional.of(COMPUTATION_A));
        when(computationResolver.resolve(null, RUN_B)).thenReturn(Optional.of(COMPUTATION_B));
    }

    private void stubStats(UUID runId, EvalSummaryMatchStats stats) {
        if (RUN_A.equals(runId)) {
            when(evalSummaryRepository.countMatches(RUN_A, COMPUTATION_A, RUN_B, COMPUTATION_B))
                    .thenReturn(stats);
        } else {
            when(evalSummaryRepository.countMatches(RUN_B, COMPUTATION_B, RUN_A, COMPUTATION_A))
                    .thenReturn(stats);
        }
    }

    private void stubUnmatched() {
        when(evalSummaryRepository.findUnmatchedIds(any(), any(), any(), any())).thenReturn(List.of());
    }

    private static MetricScoreValueDto score() {
        return MetricScoreValueDto.builder()
                .metricScoreName("AVG")
                .metricName("A.score")
                .value(0.5)
                .build();
    }

    private static TestSuiteRunResponseDto run(UUID runId, UUID suiteId, SuiteSnapshotDto snapshot) {
        TestSuiteRunResponseDto dto = new TestSuiteRunResponseDto();
        dto.setId(runId);
        dto.setTestSuiteId(suiteId);
        dto.setSuiteSnapshot(snapshot);
        return dto;
    }

    private static SuiteSnapshotDto snapshot(OverallScoreDefinition definition) {
        SuiteSnapshotDto snapshot = new SuiteSnapshotDto();
        snapshot.setOverallScore(definition);
        return snapshot;
    }

    /** Runs the callback inline, as a real read-only transaction would, without a database. */
    private static PlatformTransactionManager directTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
