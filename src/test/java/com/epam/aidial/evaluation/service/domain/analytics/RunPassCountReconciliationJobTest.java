package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.RunPassCountReconciliationProperties;
import com.epam.aidial.evaluation.data.db.model.CompletedRunKey;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("RunPassCountReconciliationJob")
@ExtendWith(MockitoExtension.class)
class RunPassCountReconciliationJobTest {

    @Mock
    private TestSuiteRunService testSuiteRunService;

    @Mock
    private RunPassCountService runPassCountService;

    private RunPassCountReconciliationProperties properties;
    private RunPassCountReconciliationJob job;

    @BeforeEach
    void setUp() {
        properties = new RunPassCountReconciliationProperties();
        properties.setEnabled(true);
        properties.setBatchSize(2);
        properties.setMinComputationAgeMs(600_000L);
        job = new RunPassCountReconciliationJob(properties, testSuiteRunService, runPassCountService);
    }

    private static CompletedRunKey key(long createdAt) {
        return new CompletedRunKey(UUID.randomUUID(), UUID.randomUUID(), createdAt);
    }

    @Test
    @DisplayName("a batch that fails is skipped and the pass continues with the next batch")
    void failingBatchDoesNotStopThePass() {
        final List<CompletedRunKey> first = List.of(key(1), key(2));
        final List<CompletedRunKey> second = List.of(key(3));
        when(testSuiteRunService.getCompletedRunKeysAfter(null, null, 2)).thenReturn(first);
        when(testSuiteRunService.getCompletedRunKeysAfter(2L, first.get(1).id(), 2))
                .thenReturn(second);
        when(runPassCountService.reconcileBatch(first, 600_000L)).thenThrow(new IllegalStateException("db down"));
        when(runPassCountService.reconcileBatch(second, 600_000L)).thenReturn(1);

        final int written = job.reconcile();

        assertThat(written).isEqualTo(1);
        verify(runPassCountService).reconcileBatch(second, 600_000L);
    }

    @Test
    @DisplayName("a disabled job reads nothing and writes nothing")
    void disabledIsNoOp() {
        properties.setEnabled(false);

        assertThat(job.reconcile()).isZero();

        verify(testSuiteRunService, never()).getCompletedRunKeysAfter(any(), any(), anyInt());
        verify(runPassCountService, never()).reconcileBatch(any(), anyLong());
    }
}
