package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("InProcessEvaluationExecutor")
@ExtendWith(MockitoExtension.class)
class InProcessEvaluationExecutorTest {

    private static final long FIXED_NOW_MS = 1_000_000L;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Mock
    private EvaluationWorker evaluationWorker;

    @Mock
    private ResultBatchWriter resultBatchWriter;

    private final Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS), ZoneOffset.UTC);

    private TestCaseRunResultFactory testCaseRunResultFactory;

    private InProcessEvaluationExecutor executor;

    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final int ASYNC_TIMEOUT_MS = 5000;

    @BeforeEach
    void setUp() {
        testCaseRunResultFactory = new TestCaseRunResultFactory(new ObjectMapper());
        executor = new InProcessEvaluationExecutor(
                testCaseRepository,
                testCaseRunInputRepository,
                evaluationWorker,
                resultBatchWriter,
                fixedClock,
                testCaseRunResultFactory);
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

    private ResultBatchWriter.RunBuffer stubCreateBuffer() {
        ResultBatchWriter.RunBuffer buffer = new ResultBatchWriter.RunBuffer(100, UUID.randomUUID(), SUITE_ID, 10);
        when(resultBatchWriter.createBuffer(anyInt(), any(UUID.class), any(UUID.class), anyInt()))
                .thenReturn(buffer);
        return buffer;
    }

    // ------------------------------------------------------------------
    // Snapshot path (inputs table)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Pages from inputs table when test_case_run_inputs rows exist (snapshot path)")
    void execute_snapshotPath_pagesFromInputsTable() {
        TestCaseRunInput input = buildInput();
        TestCaseRunResult result = buildResult(input);

        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input));
        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), anyInt(), anyList()))
                .thenReturn(result);

        executor.execute(context);

        ArgumentCaptor<TestCaseRunInput> captor = ArgumentCaptor.forClass(TestCaseRunInput.class);
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS)).execute(captor.capture(), any(), eq(0), anyList());
        assertThat(captor.getValue().getTestCaseId()).isEqualTo(input.getTestCaseId());

        verify(testCaseRepository, never()).findValidByDatasetIdExcludingIds(any(), anyList(), anyInt(), anyInt());
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result));
        verify(resultBatchWriter).flush(eq(buffer));
    }

    @Test
    @DisplayName("Falls back to live test cases when no inputs rows exist (legacy path)")
    void execute_legacyPath_pagesFromLiveTestCases() {
        TestCase testCase = buildTestCase();
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(false);
        when(testCaseRepository.findValidByDatasetIdExcludingIds(eq(DATASET_ID), eq(List.of()), eq(0), eq(100)))
                .thenReturn(List.of(testCase));
        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), anyInt(), anyList()))
                .thenReturn(TestCaseRunResult.builder()
                        .id(UUID.randomUUID())
                        .testSuiteRunId(UUID.randomUUID())
                        .testSuiteId(SUITE_ID)
                        .testCaseId(testCase.getId())
                        .testCaseName(testCase.getTestCaseName())
                        .runIndex(0)
                        .testCaseData("{}")
                        .executionStatus(ExecutionStatus.SUCCESS)
                        .execStartedAtMs(1000L)
                        .execCompletedAtMs(2000L)
                        .execDurationMs(1000L)
                        .createdAtMs(1000L)
                        .build());

        executor.execute(context);

        ArgumentCaptor<TestCaseRunInput> captor = ArgumentCaptor.forClass(TestCaseRunInput.class);
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS)).execute(captor.capture(), any(), eq(0), anyList());
        // Verify wrapper has the test case ID from the live test case
        assertThat(captor.getValue().getTestCaseId()).isEqualTo(testCase.getId());
        assertThat(captor.getValue().getTestCaseName()).isEqualTo(testCase.getTestCaseName());
        assertThat(captor.getValue().getTestCaseData()).isEqualTo(testCase.getData());

        verify(testCaseRunInputRepository, never()).findByRunId(any(), anyInt(), anyInt());
        verify(resultBatchWriter).flush(eq(buffer));
    }

    // ------------------------------------------------------------------
    // Core behavior tests (using snapshot path by default)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("execute with single test case calls worker and flushes batch")
    void execute_singleTestCase_callsWorkerAndFlushesBatch() {
        TestCaseRunInput input = buildInput();
        TestCaseRunResult result = buildResult(input);
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input));
        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), eq(0), anyList()))
                .thenReturn(result);

        executor.execute(context);

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(any(TestCaseRunInput.class), any(EvaluationContext.class), eq(0), anyList());
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result));
        verify(resultBatchWriter).flush(eq(buffer));
    }

    @Test
    @DisplayName("execute with multiple test cases processes all")
    void execute_multipleTestCases_processesAll() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(SUITE_ID, 1, 2);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));
        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenReturn(result1);
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(result2);

        executor.execute(context);

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result1));
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result2));
        verify(resultBatchWriter).flush(eq(buffer));
    }

    @Test
    @DisplayName("execute with multiple runs calls worker per run")
    void execute_multipleRuns_callsWorkerPerRun() {
        TestCaseRunInput input = buildInput();
        TestCaseRunResult result0 = buildResult(input);
        TestCaseRunResult result1 = buildResult(input);
        EvaluationContext context = buildContext(SUITE_ID, 2, 1);
        stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input));
        when(evaluationWorker.execute(eq(input), any(), eq(0), anyList())).thenReturn(result0);
        when(evaluationWorker.execute(eq(input), any(), eq(1), anyList())).thenReturn(result1);

        executor.execute(context);

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input), any(EvaluationContext.class), eq(1), anyList());
    }

    @Test
    @DisplayName("execute with cancellation before dispatch stops early")
    void execute_cancellationBeforeDispatch_stopsEarly() {
        // Cancellation signal is already set before execute
        AtomicBoolean cancellationSignal = new AtomicBoolean(true);
        EvaluationContext context = buildContextBuilder(SUITE_ID, 1, 1)
                .cancellationSignal(cancellationSignal)
                .build();

        // existsByRunId is called to decide which path to take
        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        stubCreateBuffer();

        executor.execute(context);

        // Worker should never be called because cancellation is set before dispatch
        verify(evaluationWorker, never()).execute(any(), any(), anyInt(), anyList());
        // flush is still called in finally block
        verify(resultBatchWriter).flush(any(ResultBatchWriter.RunBuffer.class));
    }

    @Test
    @DisplayName("execute with rate limiting processes all test cases")
    void execute_withRateLimiting_processesAllTestCases() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        // Use a high rate limit so the test doesn't slow down
        EvaluationContext context =
                buildContextBuilder(SUITE_ID, 1, 2).rateLimitRps(100.0).build();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));
        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenReturn(result1);
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(result2);

        executor.execute(context);

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result1));
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result2));
        verify(resultBatchWriter).flush(eq(buffer));
    }

    @Test
    @DisplayName("execute with worker exception synthesizes ERROR row and continues with other cases")
    void execute_workerException_continuesWithOtherCases() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(SUITE_ID, 1, 2);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList()))
                .thenThrow(new RuntimeException("Worker failed"));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(result2);

        executor.execute(context);

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());

        // result2 (real) is added
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result2));

        // Synthetic ERROR row added for input1
        ArgumentCaptor<TestCaseRunResult> captor = ArgumentCaptor.forClass(TestCaseRunResult.class);
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS).atLeast(2)).addResult(eq(buffer), captor.capture());

        boolean foundSynthetic = captor.getAllValues().stream()
                .anyMatch(r -> r.getExecutionStatus() == ExecutionStatus.ERROR
                        && input1.getTestCaseId().equals(r.getTestCaseId()));
        assertThat(foundSynthetic).as("synthetic ERROR row for input1").isTrue();

        verify(resultBatchWriter).flush(eq(buffer));
    }

    // ------------------------------------------------------------------
    // New tests for fix-lost-test-case-results-on-error
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Long-running workers complete even when sleep > grace period (no cancellation)")
    void shouldNotTimeoutOnLongRun_whenNoCancellation() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        // grace = 50 ms; workers sleep 200 ms — must NOT time out, no cancellation
        EvaluationContext context = buildContextBuilder(SUITE_ID, 1, 2)
                .concurrencyLevel(2)
                .cancellationGracePeriodMs(50L)
                .build();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));
        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenAnswer(inv -> {
            Thread.sleep(200);
            return result1;
        });
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenAnswer(inv -> {
            Thread.sleep(200);
            return result2;
        });

        executor.execute(context);

        verify(resultBatchWriter).addResult(eq(buffer), eq(result1));
        verify(resultBatchWriter).addResult(eq(buffer), eq(result2));
        verify(resultBatchWriter).flush(eq(buffer));
    }

    @Test
    @DisplayName("Worker exception synthesizes ERROR row with expected envelope and zeroed timing")
    void shouldSynthesizeErrorRow_whenWorkerThrows() throws Exception {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(SUITE_ID, 1, 2);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));
        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenThrow(new RuntimeException("boom"));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(result2);

        executor.execute(context);

        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result2));

        ArgumentCaptor<TestCaseRunResult> captor = ArgumentCaptor.forClass(TestCaseRunResult.class);
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS).atLeast(2)).addResult(eq(buffer), captor.capture());

        TestCaseRunResult synthetic = captor.getAllValues().stream()
                .filter(r -> r.getExecutionStatus() == ExecutionStatus.ERROR)
                .findFirst()
                .orElseThrow();

        assertThat(synthetic.getTestCaseId()).isEqualTo(input1.getTestCaseId());
        assertThat(synthetic.getExecStartedAtMs()).isEqualTo(FIXED_NOW_MS);
        assertThat(synthetic.getExecCompletedAtMs()).isEqualTo(FIXED_NOW_MS);
        assertThat(synthetic.getExecDurationMs()).isEqualTo(0L);
        assertThat(synthetic.getRetryCount()).isEqualTo(0);
        assertThat(synthetic.getCreatedAtMs()).isEqualTo(FIXED_NOW_MS);

        ObjectMapper om = new ObjectMapper();
        JsonNode envelope = om.readTree(synthetic.getResponseBody());
        assertThat(envelope.get("error").get("type").asText()).isEqualTo("RuntimeException");
        assertThat(envelope.get("error").get("message").asText()).isEqualTo("boom");
        assertThat(envelope.get("error").get("origin").asText()).isEqualTo("executor");

        verify(resultBatchWriter).flush(eq(buffer));
    }

    @Test
    @DisplayName("Cancellation mid-flight does not synthesize rows for unfinished cases")
    void shouldNotSynthesizeRows_whenCancelledMidFlight() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        AtomicBoolean cancellationSignal = new AtomicBoolean(false);
        EvaluationContext context = buildContextBuilder(SUITE_ID, 1, 2)
                .concurrencyLevel(2)
                .cancellationSignal(cancellationSignal)
                .cancellationGracePeriodMs(50L)
                .build();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));

        // Each worker flips the cancellation signal then blocks. The main thread either
        // (a) reaches the post-dispatch signal check after a worker flipped it → bounded
        //     grace path → shutdownNow → workers throw InterruptedException, OR
        // (b) reaches it before any worker ran → unbounded join path → workers sleep
        //     and return SUCCESS results normally.
        // Either way the production code MUST NOT produce a synthetic ERROR row: in (a)
        // because interruption is filtered by the worker catch (per spec D4), in (b)
        // because the workers complete successfully.
        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    cancellationSignal.set(true);
                    Thread.sleep(500);
                    TestCaseRunInput in = inv.getArgument(0);
                    return buildResult(in);
                });

        executor.execute(context);

        ArgumentCaptor<TestCaseRunResult> captor = ArgumentCaptor.forClass(TestCaseRunResult.class);
        verify(resultBatchWriter, atLeastOnce()).flush(eq(buffer));
        List<TestCaseRunResult> added = new ArrayList<>();
        try {
            verify(resultBatchWriter, atLeastOnce()).addResult(eq(buffer), captor.capture());
            added.addAll(captor.getAllValues());
        } catch (AssertionError ignored) {
            // No addResult invocations at all — that's fine; nothing was synthesized.
        }
        assertThat(added).noneMatch(r -> r.getExecutionStatus() == ExecutionStatus.ERROR);
    }

    @Test
    @DisplayName("flush is invoked exactly once at the end of normal completion")
    void shouldFlushExactlyOnce_atEnd() {
        TestCaseRunInput input = buildInput();
        TestCaseRunResult result = buildResult(input);
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input));
        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), eq(0), anyList()))
                .thenReturn(result);

        executor.execute(context);

        verify(resultBatchWriter, times(1)).flush(eq(buffer));
    }

    @Test
    @DisplayName("Catastrophic dispatch-loop failure rethrows after best-effort flush")
    void shouldRethrow_whenDispatchLoopFails() {
        EvaluationContext context = buildContext(SUITE_ID, 1, 1);
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");

        // Best-effort flush invoked (catch + finally — at least once).
        verify(resultBatchWriter, atLeastOnce()).flush(eq(buffer));
    }

    @Test
    @DisplayName("Synthesis failure is logged and does not stop subsequent test cases")
    void shouldNotRetry_whenSynthesisFails() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context =
                buildContextBuilder(SUITE_ID, 1, 2).concurrencyLevel(2).build();
        ResultBatchWriter.RunBuffer buffer = stubCreateBuffer();

        when(testCaseRunInputRepository.existsByRunId(context.getRunId())).thenReturn(true);
        when(testCaseRunInputRepository.findByRunId(context.getRunId(), 0, 100)).thenReturn(List.of(input1, input2));
        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenThrow(new RuntimeException("boom"));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(result2);

        // First addResult call is the synthetic ERROR row for input1 (ordering may vary,
        // so use a matcher selecting any ERROR-status row to throw on).
        doAnswer(inv -> {
                    TestCaseRunResult r = inv.getArgument(1);
                    if (r.getExecutionStatus() == ExecutionStatus.ERROR) {
                        throw new RuntimeException("buffer write failed");
                    }
                    return null;
                })
                .when(resultBatchWriter)
                .addResult(eq(buffer), any(TestCaseRunResult.class));

        // Must complete normally (no rethrow).
        executor.execute(context);

        // input2's real result still attempted (even though synthesis failed)
        verify(resultBatchWriter, timeout(ASYNC_TIMEOUT_MS)).addResult(eq(buffer), eq(result2));
        verify(resultBatchWriter).flush(eq(buffer));
    }
}
