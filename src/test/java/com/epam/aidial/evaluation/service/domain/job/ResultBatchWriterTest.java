package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ResultBatchWriter")
@ExtendWith(MockitoExtension.class)
class ResultBatchWriterTest {

    @Mock
    private ResultBatchWriterTransactional transactionalWriter;

    @Mock
    private TestSuiteRunSseService sseService;

    private ResultBatchWriter writer;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final int TOTAL_CASES = 10;

    @BeforeEach
    void setUp() {
        writer = new ResultBatchWriter(transactionalWriter, sseService);
    }

    private TestCaseRunResult buildResult() {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(RUN_ID)
                .testSuiteId(SUITE_ID)
                .testCaseId(UUID.randomUUID())
                .testCaseName("test")
                .runIndex(0)
                .turnIndex(0)
                .totalTurns(1)
                .testCaseData("{}")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execStartedAtMs(1000L)
                .execCompletedAtMs(2000L)
                .execDurationMs(1000L)
                .createdAtMs(1000L)
                .build();
    }

    /** Adds one single-turn conversation (one row). */
    private void addOneConversation(ResultBatchWriter.RunBuffer buffer) {
        writer.addResults(buffer, List.of(buildResult()));
    }

    /** Builds a multi-turn conversation of {@code turns} per-turn rows. */
    private List<TestCaseRunResult> conversationOf(int turns) {
        List<TestCaseRunResult> rows = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            rows.add(buildResult());
        }
        return rows;
    }

    @Test
    @DisplayName("addResults below row threshold does not flush")
    void addResults_belowThreshold_doesNotFlush() {
        int batchSize = 5;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        for (int i = 0; i < batchSize - 1; i++) {
            addOneConversation(buffer);
        }

        verify(transactionalWriter, never()).saveBatch(anyList());
        verify(sseService, never()).notifyProgress(any(), any(), anyInt(), anyInt());
        assertThat(buffer.getTotalFlushed()).isZero();
    }

    @Test
    @DisplayName("addResults at row threshold triggers flush; progress counts completed conversations")
    void addResults_atThreshold_flushes() {
        int batchSize = 3;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        for (int i = 0; i < batchSize; i++) {
            addOneConversation(buffer);
        }

        verify(transactionalWriter, times(1)).saveBatch(anyList());
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(batchSize), eq(TOTAL_CASES));
        assertThat(buffer.getTotalFlushed()).isEqualTo(batchSize);
    }

    @Test
    @DisplayName("addResults with multiple flushes increments total flushed and conversation progress correctly")
    void addResults_multipleFlushes_incrementsTotalFlushed() {
        int batchSize = 2;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        addOneConversation(buffer);
        addOneConversation(buffer);

        addOneConversation(buffer);
        addOneConversation(buffer);

        verify(transactionalWriter, times(2)).saveBatch(anyList());
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(2), eq(TOTAL_CASES));
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(4), eq(TOTAL_CASES));
        assertThat(buffer.getTotalFlushed()).isEqualTo(4);
    }

    @Test
    @DisplayName("a multi-turn conversation buffers all its per-turn rows but advances progress by one")
    void addResults_multiTurnConversation_buffersRowsAdvancesProgressByOne() {
        int batchSize = 10;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        writer.addResults(buffer, conversationOf(3));
        verify(transactionalWriter, never()).saveBatch(anyList());
        assertThat(buffer.getConversationsCompleted()).isEqualTo(1);

        writer.flush(buffer);

        assertThat(buffer.getTotalFlushed()).isEqualTo(3);
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(1), eq(TOTAL_CASES));
    }

    @Test
    @DisplayName("flush with buffered results writes all remaining")
    void flush_withBufferedResults_writesAll() {
        int batchSize = 10;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        addOneConversation(buffer);
        addOneConversation(buffer);
        addOneConversation(buffer);

        // No flush yet because below threshold
        verify(transactionalWriter, never()).saveBatch(anyList());

        writer.flush(buffer);

        verify(transactionalWriter, times(1)).saveBatch(anyList());
        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(3), eq(TOTAL_CASES));
        assertThat(buffer.getTotalFlushed()).isEqualTo(3);
    }

    @Test
    @DisplayName("flush with empty buffer does nothing")
    void flush_emptyBuffer_doesNothing() {
        int batchSize = 5;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        writer.flush(buffer);

        verify(transactionalWriter, never()).saveBatch(anyList());
        verify(sseService, never()).notifyProgress(any(), any(), anyInt(), anyInt());
        assertThat(buffer.getTotalFlushed()).isZero();
    }

    @Test
    @DisplayName("addResults sends progress SSE after flush")
    void addResults_sendsProgressSseAfterFlush() {
        int batchSize = 2;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        addOneConversation(buffer);
        addOneConversation(buffer);

        verify(sseService, times(1)).notifyProgress(eq(RUN_ID), eq(SUITE_ID), eq(2), eq(TOTAL_CASES));
    }

    @Test
    @DisplayName("flush when SSE fails does not throw exception")
    void flush_sseFailure_doesNotThrow() {
        int batchSize = 10;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        addOneConversation(buffer);

        doThrow(new RuntimeException("SSE connection lost"))
                .when(sseService)
                .notifyProgress(any(), any(), anyInt(), anyInt());

        assertThatCode(() -> writer.flush(buffer)).doesNotThrowAnyException();

        // Batch was still persisted despite SSE failure
        verify(transactionalWriter, times(1)).saveBatch(anyList());
        assertThat(buffer.getTotalFlushed()).isEqualTo(1);
    }

    @Test
    @DisplayName("saveBatch receives correct batch contents")
    @SuppressWarnings("unchecked")
    void addResults_saveBatchReceivesCorrectBatch() {
        int batchSize = 2;
        ResultBatchWriter.RunBuffer buffer = writer.createBuffer(batchSize, RUN_ID, SUITE_ID, TOTAL_CASES);

        TestCaseRunResult result1 = buildResult();
        TestCaseRunResult result2 = buildResult();

        writer.addResults(buffer, List.of(result1));
        writer.addResults(buffer, List.of(result2));

        ArgumentCaptor<List<TestCaseRunResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionalWriter).saveBatch(captor.capture());

        List<TestCaseRunResult> captured = captor.getValue();
        assertThat(captured).hasSize(2);
        assertThat(captured).containsExactly(result1, result2);
    }
}
