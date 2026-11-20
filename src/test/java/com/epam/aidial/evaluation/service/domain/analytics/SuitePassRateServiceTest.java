package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.Collection;
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

    private RunPassCountService runPassCountService;
    private SuitePassRateService service;

    @BeforeEach
    void setUp() {
        runPassCountService = mock(RunPassCountService.class);
        service = new SuitePassRateService(runPassCountService, new PassRateCalculator(), directTransactionManager());
    }

    private void stub(Map<UUID, List<RunPassCount>> recordedRuns) {
        when(runPassCountService.getLatestRecordedRuns(anyCollection(), eq(2))).thenReturn(recordedRuns);
    }

    private static RunPassCount counts(int passed, int total) {
        return RunPassCount.builder()
                .testSuiteRunId(UUID.randomUUID())
                .computationId(UUID.randomUUID())
                .passedCount(passed)
                .totalCount(total)
                .build();
    }

    @Test
    @DisplayName("two suites, one with a previous run: latest mean 0.75 over 2, previous mean 0.0 over 1")
    void twoSuitesOneWithPreviousRun() {
        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        stub(Map.of(suiteA, List.of(counts(1, 2), counts(0, 2)), suiteB, List.of(counts(2, 2))));

        SuitePassRateSummaryDto summary = service.summarize(List.of(suiteA, suiteB));

        assertThat(summary.getPassRate()).isEqualTo(0.75);
        assertThat(summary.getSuiteCount()).isEqualTo(2);
        assertThat(summary.getPreviousPassRate()).isEqualTo(0.0);
        assertThat(summary.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("no recorded runs: both rates are null and counts are 0")
    void noRecordedRuns() {
        stub(Map.of());

        SuitePassRateSummaryDto summary = service.summarize(List.of(UUID.randomUUID()));

        assertThat(summary.getPassRate()).isNull();
        assertThat(summary.getPreviousPassRate()).isNull();
        assertThat(summary.getSuiteCount()).isZero();
        assertThat(summary.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("the rate is the stored passed count over the total count: 1 of 2 is 0.5")
    void rateIsPassedOverTotal() {
        UUID suite = UUID.randomUUID();
        stub(Map.of(suite, List.of(counts(1, 2))));

        assertThat(service.summarize(List.of(suite)).getPassRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("2 of 3 test cases passed give 2/3")
    void twoOfThree() {
        UUID suite = UUID.randomUUID();
        stub(Map.of(suite, List.of(counts(2, 3))));

        assertThat(service.summarize(List.of(suite)).getPassRate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("each suite counts once: a 100-test-case suite at 1.0 and a 2-test-case suite at 0.0 average 0.5")
    void eachSuiteCountsOnce() {
        UUID big = UUID.randomUUID();
        UUID small = UUID.randomUUID();
        stub(Map.of(big, List.of(counts(100, 100)), small, List.of(counts(0, 2))));

        assertThat(service.summarize(List.of(big, small)).getPassRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a latest run with a total of 0 contributes nothing and is not replaced by an older run")
    void latestRunWithZeroTotalContributesNothing() {
        UUID suite = UUID.randomUUID();
        stub(Map.of(suite, List.of(counts(0, 0), counts(2, 2))));

        SuitePassRateSummaryDto summary = service.summarize(List.of(suite));

        assertThat(summary.getPassRate()).isNull();
        assertThat(summary.getSuiteCount()).isZero();
        assertThat(summary.getPreviousPassRate()).isEqualTo(1.0);
        assertThat(summary.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a completed run not recorded yet is absent: the older recorded run is the latest")
    void unrecordedRunIsAbsent() {
        UUID suite = UUID.randomUUID();
        stub(Map.of(suite, List.of(counts(2, 2))));

        SuitePassRateSummaryDto summary = service.summarize(List.of(suite));

        assertThat(summary.getPassRate()).isEqualTo(1.0);
        assertThat(summary.getSuiteCount()).isEqualTo(1);
        assertThat(summary.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("duplicate suite ids are removed before the read")
    void duplicateIdsAreRemoved() {
        UUID suite = UUID.randomUUID();
        stub(Map.of());

        service.summarize(List.of(suite, suite, suite));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(runPassCountService).getLatestRecordedRuns(captor.capture(), eq(2));
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
        verify(runPassCountService, never()).getLatestRecordedRuns(anyCollection(), anyInt());
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
        verify(runPassCountService, never()).getLatestRecordedRuns(anyCollection(), anyInt());
    }

    /** Runs the callback inline, as a real read-only transaction would, without a database. */
    private static PlatformTransactionManager directTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
