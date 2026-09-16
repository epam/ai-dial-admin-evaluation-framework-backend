package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.PassRateProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassRateStats;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRunRef;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunPassRateDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("PassRateService")
class PassRateServiceTest {

    private static final UUID SUITE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RUN_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID RUN_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID COMPUTATION_A = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID COMPUTATION_B = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final int DEFAULT_LAST_N = 10;
    private static final int MAX_LAST_N = 100;

    private final TestSuiteService testSuiteService = mock(TestSuiteService.class);
    private final TestSuiteRunService testSuiteRunService = mock(TestSuiteRunService.class);
    private final EvalSummaryRepository evalSummaryRepository = mock(EvalSummaryRepository.class);

    private PassRateService service;

    @BeforeEach
    void setUp() {
        PassRateProperties properties = new PassRateProperties();
        properties.setDefaultLastN(DEFAULT_LAST_N);
        properties.setMaxLastN(MAX_LAST_N);
        service = new PassRateService(
                testSuiteService, testSuiteRunService, evalSummaryRepository, properties, directTransactionManager());
    }

    @Test
    @DisplayName("Should apply the configured default lastN when none is given")
    void shouldApplyDefaultLastN() {
        stubSuiteExists();
        when(testSuiteRunService.findRecentRuns(SUITE_ID, DEFAULT_LAST_N)).thenReturn(List.of());

        service.getPassRate(SUITE_ID, null);

        verify(testSuiteRunService).findRecentRuns(SUITE_ID, DEFAULT_LAST_N);
    }

    @Test
    @DisplayName("Should reject lastN above the configured max before any meta call")
    void shouldRejectTooLargeLastN() {
        assertThatThrownBy(() -> service.getPassRate(SUITE_ID, MAX_LAST_N + 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("analytics.pass-rate.max-last-n");

        verify(testSuiteService, never()).getById(any());
        verify(testSuiteRunService, never()).findRecentRuns(any(), anyInt());
        verify(evalSummaryRepository, never()).countPassRateByLatestComputation(any());
    }

    @Test
    @DisplayName("Should propagate not-found for an unknown suite without touching runs or analytics")
    void shouldPropagateUnknownSuite() {
        when(testSuiteService.getById(SUITE_ID)).thenThrow(new EntityNotFoundException("nope"));

        assertThatThrownBy(() -> service.getPassRate(SUITE_ID, 5)).isInstanceOf(EntityNotFoundException.class);

        verify(testSuiteRunService, never()).findRecentRuns(any(), anyInt());
        verify(evalSummaryRepository, never()).countPassRateByLatestComputation(any());
    }

    @Test
    @DisplayName("Should preserve meta ordering when the analytics aggregate returns rows shuffled")
    void shouldPreserveMetaOrdering() {
        stubSuiteExists();
        when(testSuiteRunService.findRecentRuns(SUITE_ID, 5))
                .thenReturn(List.of(
                        new TestSuiteRunRef(RUN_A, "COMPLETED", 3000L),
                        new TestSuiteRunRef(RUN_B, "COMPLETED", 2000L)));
        // Aggregate returned in the reverse order of the meta list.
        when(evalSummaryRepository.countPassRateByLatestComputation(List.of(RUN_A, RUN_B)))
                .thenReturn(List.of(stats(RUN_B, COMPUTATION_B), stats(RUN_A, COMPUTATION_A)));

        SuitePassRateResponseDto response = service.getPassRate(SUITE_ID, 5);

        assertThat(response.getRuns())
                .extracting(RunPassRateDto::getTestSuiteRunId)
                .containsExactly(RUN_A, RUN_B);
    }

    @Test
    @DisplayName("Should drop runs the aggregate returns no row for")
    void shouldDropRunsMissingFromAggregate() {
        stubSuiteExists();
        when(testSuiteRunService.findRecentRuns(SUITE_ID, 5))
                .thenReturn(List.of(
                        new TestSuiteRunRef(RUN_A, "COMPLETED", 3000L),
                        new TestSuiteRunRef(RUN_B, "FAILED", 2000L),
                        new TestSuiteRunRef(RUN_C, "COMPLETED", 1000L)));
        // Only RUN_A and RUN_C have eval summaries; RUN_B never produced any.
        when(evalSummaryRepository.countPassRateByLatestComputation(List.of(RUN_A, RUN_B, RUN_C)))
                .thenReturn(List.of(stats(RUN_A, COMPUTATION_A), stats(RUN_C, COMPUTATION_B)));

        SuitePassRateResponseDto response = service.getPassRate(SUITE_ID, 5);

        assertThat(response.getRuns())
                .extracting(RunPassRateDto::getTestSuiteRunId)
                .containsExactly(RUN_A, RUN_C);
    }

    @Test
    @DisplayName("Should copy status and runCreatedAtMs from the meta ref")
    void shouldCopyStatusAndCreatedAt() {
        stubSuiteExists();
        when(testSuiteRunService.findRecentRuns(SUITE_ID, 5))
                .thenReturn(List.of(new TestSuiteRunRef(RUN_A, "RUNNING", 4242L)));
        when(evalSummaryRepository.countPassRateByLatestComputation(List.of(RUN_A)))
                .thenReturn(List.of(stats(RUN_A, COMPUTATION_A)));

        RunPassRateDto dto = service.getPassRate(SUITE_ID, 5).getRuns().get(0);

        assertThat(dto.getStatus()).isEqualTo("RUNNING");
        assertThat(dto.getRunCreatedAtMs()).isEqualTo(4242L);
        assertThat(dto.getComputationId()).isEqualTo(COMPUTATION_A);
        assertThat(dto.getFailedCount()).isEqualTo(1L);
        assertThat(dto.getSuccessPassedCount()).isEqualTo(2L);
        assertThat(dto.getSuccessNotPassedCount()).isEqualTo(3L);
        assertThat(dto.getSuccessNoVerdictCount()).isEqualTo(4L);
        assertThat(dto.getTotalCount()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Should return an empty run list without calling analytics when the suite has no runs")
    void shouldReturnEmptyRunsWithoutAnalyticsCall() {
        stubSuiteExists();
        when(testSuiteRunService.findRecentRuns(SUITE_ID, DEFAULT_LAST_N)).thenReturn(List.of());

        SuitePassRateResponseDto response = service.getPassRate(SUITE_ID, null);

        assertThat(response.getTestSuiteId()).isEqualTo(SUITE_ID);
        assertThat(response.getRuns()).isEmpty();
        verify(evalSummaryRepository, never()).countPassRateByLatestComputation(any());
    }

    private void stubSuiteExists() {
        when(testSuiteService.getById(SUITE_ID)).thenReturn(null);
    }

    private static RunPassRateStats stats(UUID runId, UUID computationId) {
        return new RunPassRateStats(runId, computationId, 1L, 2L, 3L, 4L, 10L);
    }

    private static PlatformTransactionManager directTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
