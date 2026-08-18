package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.ResultBatchWriter;
import com.epam.aidial.evaluation.runner.job.TestCaseRunner;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("InProcessEvaluationExecutor")
@ExtendWith(MockitoExtension.class)
class InProcessEvaluationExecutorTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Mock
    private TestCaseRunnerFactory testCaseRunnerFactory;

    @Mock
    private TestCaseRunner testCaseRunner;

    @Mock
    private PostgresResultBatchWriterFactory resultBatchWriterFactory;

    @Mock
    private ResultBatchWriter writer;

    private InProcessEvaluationExecutor executor;

    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executor = new InProcessEvaluationExecutor(
                testCaseRepository, testCaseRunInputRepository, testCaseRunnerFactory, resultBatchWriterFactory);
    }

    private EvaluationContext buildContext(UUID suiteId, int numberOfRuns, int numberOfTestCases) {
        return buildContextBuilder(suiteId, numberOfRuns, numberOfTestCases).build();
    }

    private EvaluationContext.EvaluationContextBuilder buildContextBuilder(
            UUID suiteId, int numberOfRuns, int numberOfTestCases) {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(suiteId)
                .datasetId(DATASET_ID)
                .numberOfRuns(numberOfRuns)
                .numberOfTestCases(numberOfTestCases)
                .concurrencyLevel(1)
                .requestTimeoutMs(30000L)
                .maxRetries(0)
                .retryDelayMs(100L)
                .retryBackoffMultiplier(2.0)
                .maxRetryDelayMs(1000L)
                .resultBatchSize(100)
                .maxResponseSizeBytes(5242880L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .token("test-token")
                .createdAtMs(System.currentTimeMillis())
                .snapshotResponseColumns(List.of());
    }

    private TestCaseRunInput buildInput() {
        return TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .position(0)
                .testCaseId(UUID.randomUUID())
                .testCaseName("test-case-1")
                .testCaseData("{}")
                .build();
    }

    private TestCase buildTestCase() {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(DATASET_ID)
                .testCaseName("test-case-1")
                .data("{}")
                .valid(true)
                .build();
    }

    private TestCaseRunResult buildResult(TestCaseRunInput input) {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .testSuiteId(SUITE_ID)
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(0)
                .testCaseData("{}")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execStartedAtMs(1000L)
                .execCompletedAtMs(2000L)
                .execDurationMs(1000L)
                .createdAtMs(1000L)
                .build();
    }

    private void stubCreateWriter() {
        when(resultBatchWriterFactory.createWriter(anyInt(), any(UUID.class), any(UUID.class), anyInt()))
                .thenReturn(writer);
    }

    private void stubCreateRunner(EvaluationContext context) {
        when(testCaseRunnerFactory.create(eq(context), anyList(), eq(writer))).thenReturn(testCaseRunner);
    }

    // ------------------------------------------------------------------
    // Snapshot path (inputs table)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Pages from inputs table when test_case_run_inputs rows exist (snapshot path)")
    void execute_snapshotPath_pagesFromInputsTable() {
        TestCaseRunInput input = buildInput();
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        stubCreateWriter();
        stubCreateRunner(context);

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input));

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCaseRunInput>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRunner).submit(pageCaptor.capture());
        assertThat(pageCaptor.getValue())
                .extracting(TestCaseRunInput::getTestCaseId)
                .containsExactly(input.getTestCaseId());

        verify(testCaseRepository, never()).findValidByDatasetId(any(), anyInt(), anyInt());
        verify(testCaseRunner).awaitCompletion();
        verify(writer).flush();
    }

    @Test
    @DisplayName("Falls back to live test cases when no inputs rows exist (legacy path)")
    void execute_legacyPath_pagesFromLiveTestCases() {
        TestCase testCase = buildTestCase();
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        stubCreateWriter();
        stubCreateRunner(context);

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(false);
        when(testCaseRepository.findValidByDatasetId(eq(DATASET_ID), eq(0), eq(100)))
                .thenReturn(List.of(testCase));

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCaseRunInput>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRunner).submit(pageCaptor.capture());
        TestCaseRunInput wrapped = pageCaptor.getValue().get(0);
        assertThat(wrapped.getTestCaseId()).isEqualTo(testCase.getId());
        assertThat(wrapped.getTestCaseName()).isEqualTo(testCase.getTestCaseName());
        assertThat(wrapped.getTestCaseData()).isEqualTo(testCase.getData());

        verify(testCaseRunInputRepository, never()).findByRunId(any(), anyInt(), anyInt());
        verify(testCaseRunner).awaitCompletion();
        verify(writer).flush();
    }

    // ------------------------------------------------------------------
    // Delegation / result-forwarding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The runner created by the factory receives the whole page and is awaited once")
    void execute_submitsWholePageAndAwaitsCompletionOnce() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        EvaluationContext context = buildContext(SUITE_ID, 1, 2);
        stubCreateWriter();
        stubCreateRunner(context);

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));

        executor.execute(context);

        verify(testCaseRunnerFactory).create(eq(context), anyList(), eq(writer));
        verify(testCaseRunner).submit(eq(List.of(input1, input2)));
        verify(testCaseRunner, times(1)).awaitCompletion();
        verify(writer).flush();
    }

    @Test
    @DisplayName("flush is invoked exactly once at the end of normal completion")
    void shouldFlushExactlyOnce_atEnd() {
        TestCaseRunInput input = buildInput();
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        stubCreateWriter();
        stubCreateRunner(context);

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input));

        executor.execute(context);

        verify(writer, times(1)).flush();
    }

    @Test
    @DisplayName("Catastrophic dispatch-loop failure rethrows after best-effort flush")
    void shouldRethrow_whenDispatchLoopFails() {
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        stubCreateWriter();
        stubCreateRunner(context);

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");

        verify(testCaseRunner, never()).submit(any());
        verify(testCaseRunner, never()).awaitCompletion();
        // Best-effort flush invoked (catch + finally — at least once).
        verify(writer, atLeastOnce()).flush();
    }
}
