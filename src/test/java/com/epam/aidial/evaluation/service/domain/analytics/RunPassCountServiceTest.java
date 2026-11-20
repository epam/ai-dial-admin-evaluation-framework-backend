package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.model.LatestComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.PassCountTotals;
import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunPassCountRepository;
import com.epam.aidial.evaluation.data.db.model.CompletedRunKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("RunPassCountService")
@ExtendWith(MockitoExtension.class)
class RunPassCountServiceTest {

    private static final long RUN_CREATED_AT = 1_000L;

    @Mock
    private RunPassCountRepository runPassCountRepository;

    @Mock
    private EvalSummaryRepository evalSummaryRepository;

    private RunPassCountService service;
    private final UUID runId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunPassCountService(runPassCountRepository, evalSummaryRepository, Clock.systemUTC());
    }

    private RunPassCount insertedRow() {
        final ArgumentCaptor<RunPassCount> captor = ArgumentCaptor.forClass(RunPassCount.class);
        verify(runPassCountRepository).insertIfAbsent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a computation with neither summaries nor score rows is stored as the null computation at time 0")
    void storesNullComputationWhenComputationHasNoRows() {
        final UUID computationId = UUID.randomUUID();
        when(runPassCountRepository.countForComputation(runId, computationId)).thenReturn(new PassCountTotals(0, 0));
        when(runPassCountRepository.insertIfAbsent(any())).thenReturn(true);

        final boolean inserted = service.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertThat(inserted).isTrue();
        final RunPassCount row = insertedRow();
        assertThat(row.getComputationId()).isNull();
        assertThat(row.getPassedCount()).isZero();
        assertThat(row.getTotalCount()).isZero();
        assertThat(row.getComputedAtMs()).isZero();
        assertThat(row.getRunCreatedAtMs()).isEqualTo(RUN_CREATED_AT);
    }

    @Test
    @DisplayName("a null computation id stores the null-computation 0-of-0 row without counting")
    void storesNullComputationWhenComputationIdIsNull() {
        when(runPassCountRepository.insertIfAbsent(any())).thenReturn(true);

        service.record(runId, suiteId, null, RUN_CREATED_AT);

        final RunPassCount row = insertedRow();
        assertThat(row.getComputationId()).isNull();
        assertThat(row.getTotalCount()).isZero();
        assertThat(row.getComputedAtMs()).isZero();
    }

    @Test
    @DisplayName("a computation with rows is stored with its counts and the summaries' timestamp")
    void storesCountsAndComputationTimestamp() {
        final UUID computationId = UUID.randomUUID();
        when(runPassCountRepository.countForComputation(runId, computationId)).thenReturn(new PassCountTotals(2, 3));
        when(runPassCountRepository.findComputedAtMs(runId, computationId)).thenReturn(Optional.of(77L));
        when(runPassCountRepository.insertIfAbsent(any())).thenReturn(true);

        service.record(runId, suiteId, computationId, RUN_CREATED_AT);

        final RunPassCount row = insertedRow();
        assertThat(row.getComputationId()).isEqualTo(computationId);
        assertThat(row.getPassedCount()).isEqualTo(2);
        assertThat(row.getTotalCount()).isEqualTo(3);
        assertThat(row.getComputedAtMs()).isEqualTo(77L);
    }

    @Test
    @DisplayName("a batch reports the rows written and the runs deferred because their computation is too recent")
    void reconcileBatchReportsWrittenAndDeferred() {
        final long now = 10_000_000L;
        final long minAgeMs = 600_000L;
        service = new RunPassCountService(
                runPassCountRepository, evalSummaryRepository, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
        final CompletedRunKey due = new CompletedRunKey(UUID.randomUUID(), suiteId, 1L);
        final CompletedRunKey tooRecent = new CompletedRunKey(UUID.randomUUID(), suiteId, 2L);
        final CompletedRunKey recorded = new CompletedRunKey(UUID.randomUUID(), suiteId, 3L);
        final UUID dueComputation = UUID.randomUUID();
        final UUID recentComputation = UUID.randomUUID();
        final UUID recordedComputation = UUID.randomUUID();
        when(evalSummaryRepository.findLatestComputations(List.of(due.id(), tooRecent.id(), recorded.id())))
                .thenReturn(Map.of(
                        due.id(), new LatestComputation(dueComputation, now - minAgeMs),
                        tooRecent.id(), new LatestComputation(recentComputation, now - minAgeMs + 1),
                        recorded.id(), new LatestComputation(recordedComputation, 0L)));
        when(runPassCountRepository.findRecordedComputations(List.of(due.id(), tooRecent.id(), recorded.id())))
                .thenReturn(List.of(new RecordedComputation(recorded.id(), recordedComputation)));
        when(runPassCountRepository.countForComputation(due.id(), dueComputation))
                .thenReturn(new PassCountTotals(1, 2));
        when(runPassCountRepository.findComputedAtMs(due.id(), dueComputation)).thenReturn(Optional.of(5L));
        when(runPassCountRepository.insertIfAbsent(any())).thenReturn(true);

        final ReconciledBatch batch = service.reconcileBatch(List.of(due, tooRecent, recorded), minAgeMs);

        assertThat(batch).isEqualTo(new ReconciledBatch(1, 1));
        assertThat(insertedRow().getTestSuiteRunId()).isEqualTo(due.id());
    }
}
