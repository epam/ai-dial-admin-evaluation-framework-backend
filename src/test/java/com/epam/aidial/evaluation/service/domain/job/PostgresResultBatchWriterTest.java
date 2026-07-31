package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("PostgresResultBatchWriter")
@ExtendWith(MockitoExtension.class)
class PostgresResultBatchWriterTest {

    @Mock
    private TestCaseRunResultRepository resultRepository;

    @Mock
    private TestSuiteRunSseService sseService;

    private TransactionTemplate transactionTemplate;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final int TOTAL_CASES = 10;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        transactionTemplate = new TransactionTemplate(txManager);
    }

    private PostgresResultBatchWriter buildWriter(int batchSize) {
        return new PostgresResultBatchWriter(
                resultRepository, sseService, transactionTemplate, batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);
    }

    private TestCaseRunResult buildResult() {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(RUN_ID)
                .testSuiteId(SUITE_ID)
                .testCaseId(UUID.randomUUID())
                .testCaseName("test")
                .runIndex(0)
                .testCaseData("{}")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execStartedAtMs(1000L)
                .execCompletedAtMs(2000L)
                .execDurationMs(1000L)
                .createdAtMs(1000L)
                .build();
    }

    @Test
    @DisplayName("addResults below threshold does not flush")
    void addResults_belowThreshold_doesNotFlush() {
        int batchSize = 5;
        PostgresResultBatchWriter writer = buildWriter(batchSize);

        for (int i = 0; i < batchSize - 1; i++) {
            writer.addResults(List.of(buildResult()));
        }

        verify(resultRepository, never()).saveAll(anyList());
        verify(sseService, never()).notifyProgress(any(), any(), anyInt(), anyInt());
        assertThat(writer.getTotalFlushed()).isZero();
    }

    @Test
    @DisplayName("addResults at threshold triggers flush")
    void addResults_atThreshold_flushes() {
        int batchSize = 3;
        PostgresResultBatchWriter writer = buildWriter(batchSize);

        for (int i = 0; i < batchSize; i++) {
            writer.addResults(List.of(buildResult()));
        }

        verify(resultRepository, times(1)).saveAll(anyList());
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(batchSize), eq(TOTAL_CASES));
        assertThat(writer.getTotalFlushed()).isEqualTo(batchSize);
    }

    @Test
    @DisplayName("addResults with multiple flushes increments total flushed correctly")
    void addResults_multipleFlushes_incrementsTotalFlushed() {
        int batchSize = 2;
        PostgresResultBatchWriter writer = buildWriter(batchSize);

        // First batch of 2
        writer.addResults(List.of(buildResult()));
        writer.addResults(List.of(buildResult()));

        // Second batch of 2
        writer.addResults(List.of(buildResult()));
        writer.addResults(List.of(buildResult()));

        verify(resultRepository, times(2)).saveAll(anyList());
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(2), eq(TOTAL_CASES));
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(4), eq(TOTAL_CASES));
        assertThat(writer.getTotalFlushed()).isEqualTo(4);
    }

    @Test
    @DisplayName("flush with buffered results writes all remaining")
    void flush_withBufferedResults_writesAll() {
        int batchSize = 10;
        PostgresResultBatchWriter writer = buildWriter(batchSize);

        writer.addResults(List.of(buildResult()));
        writer.addResults(List.of(buildResult()));
        writer.addResults(List.of(buildResult()));

        // No flush yet because below threshold
        verify(resultRepository, never()).saveAll(anyList());

        writer.flush();

        verify(resultRepository, times(1)).saveAll(anyList());
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(3), eq(TOTAL_CASES));
        assertThat(writer.getTotalFlushed()).isEqualTo(3);
    }

    @Test
    @DisplayName("flush with empty buffer does nothing")
    void flush_emptyBuffer_doesNothing() {
        PostgresResultBatchWriter writer = buildWriter(5);

        writer.flush();

        verify(resultRepository, never()).saveAll(anyList());
        verify(sseService, never()).notifyProgress(any(), any(), anyInt(), anyInt());
        assertThat(writer.getTotalFlushed()).isZero();
    }

    @Test
    @DisplayName("addResults sends progress SSE after flush")
    void addResults_sendsProgressSseAfterFlush() {
        int batchSize = 2;
        PostgresResultBatchWriter writer = buildWriter(batchSize);

        writer.addResults(List.of(buildResult()));
        writer.addResults(List.of(buildResult()));

        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(2), eq(TOTAL_CASES));
    }

    @Test
    @DisplayName("flush when SSE fails does not throw exception")
    void flush_sseFailure_doesNotThrow() {
        PostgresResultBatchWriter writer = buildWriter(10);

        writer.addResults(List.of(buildResult()));

        doThrow(new RuntimeException("SSE connection lost"))
                .when(sseService)
                .notifyProgress(any(), any(), anyInt(), anyInt());

        assertThatCode(writer::flush).doesNotThrowAnyException();

        // Batch was still persisted despite SSE failure
        verify(resultRepository, times(1)).saveAll(anyList());
        assertThat(writer.getTotalFlushed()).isEqualTo(1);
    }

    @Test
    @DisplayName("saveAll receives correct batch contents")
    void addResults_saveAllReceivesCorrectBatch() {
        int batchSize = 2;
        PostgresResultBatchWriter writer = buildWriter(batchSize);

        TestCaseRunResult result1 = buildResult();
        TestCaseRunResult result2 = buildResult();

        writer.addResults(List.of(result1));
        writer.addResults(List.of(result2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCaseRunResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(resultRepository).saveAll(captor.capture());

        List<TestCaseRunResult> captured = captor.getValue();
        assertThat(captured).hasSize(2);
        assertThat(captured).containsExactly(result1, result2);
    }
}
