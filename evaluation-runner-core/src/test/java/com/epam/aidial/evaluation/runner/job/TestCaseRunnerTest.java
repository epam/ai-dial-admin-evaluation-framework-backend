package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCaseRunner")
@ExtendWith(MockitoExtension.class)
class TestCaseRunnerTest {

    private static final long FIXED_NOW_MS = 1_000_000L;
    private static final int ASYNC_TIMEOUT_MS = 5000;
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    @Mock
    private EvaluationWorker evaluationWorker;

    @Mock
    private ResultBatchWriter resultsWriter;

    private final Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS), ZoneOffset.UTC);

    private TestCaseRunResultFactory testCaseRunResultFactory;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        testCaseRunResultFactory = new TestCaseRunResultFactory(new ObjectMapper());
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private TestCaseRunner createRunner(EvaluationContext context) {
        return new TestCaseRunner(
                evaluationWorker, testCaseRunResultFactory, fixedClock, context, List.of(), resultsWriter);
    }

    private EvaluationContext buildContext(int numberOfRuns, int numberOfTestCases) {
        return buildContextBuilder(numberOfRuns, numberOfTestCases).build();
    }

    private EvaluationContext.EvaluationContextBuilder buildContextBuilder(int numberOfRuns, int numberOfTestCases) {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(SUITE_ID)
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
                .executor(executor)
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

    @Test
    @DisplayName("submit with single test case calls worker and delivers results to writer")
    void submit_singleTestCase_callsWorkerAndDeliversResults() {
        TestCaseRunInput input = buildInput();
        TestCaseRunResult result = buildResult(input);
        EvaluationContext context = buildContext(1, 1);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), eq(0), anyList()))
                .thenReturn(List.of(result));

        assertThat(runner.submit(List.of(input))).isTrue();
        runner.awaitCompletion();

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(any(TestCaseRunInput.class), any(EvaluationContext.class), eq(0), anyList());
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result)));
    }

    @Test
    @DisplayName("submit with multiple test cases processes all")
    void submit_multipleTestCases_processesAll() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(1, 2);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenReturn(List.of(result1));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(List.of(result2));

        runner.submit(List.of(input1, input2));
        runner.awaitCompletion();

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result1)));
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result2)));
    }

    @Test
    @DisplayName("submit with multiple runs calls worker per run")
    void submit_multipleRuns_callsWorkerPerRun() {
        TestCaseRunInput input = buildInput();
        TestCaseRunResult result0 = buildResult(input);
        TestCaseRunResult result1 = buildResult(input);
        EvaluationContext context = buildContext(2, 1);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input), any(), eq(0), anyList())).thenReturn(List.of(result0));
        when(evaluationWorker.execute(eq(input), any(), eq(1), anyList())).thenReturn(List.of(result1));

        runner.submit(List.of(input));
        runner.awaitCompletion();

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input), any(EvaluationContext.class), eq(1), anyList());
    }

    @Test
    @DisplayName("submit with rate limiting processes all test cases")
    void submit_withRateLimiting_processesAllTestCases() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);

        // Use a high rate limit so the test doesn't slow down
        EvaluationContext context =
                buildContextBuilder(1, 2).rateLimitRps(100.0).build();
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenReturn(List.of(result1));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(List.of(result2));

        runner.submit(List.of(input1, input2));
        runner.awaitCompletion();

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result1)));
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result2)));
    }

    @Test
    @DisplayName("submit with worker exception synthesizes ERROR row and continues with other cases")
    void submit_workerException_continuesWithOtherCases() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(1, 2);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList()))
                .thenThrow(new RuntimeException("Worker failed"));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(List.of(result2));

        runner.submit(List.of(input1, input2));
        runner.awaitCompletion();

        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, timeout(ASYNC_TIMEOUT_MS))
                .execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());

        // result2 (real) is delivered
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result2)));

        // Synthetic ERROR row delivered for input1
        ArgumentCaptor<List<TestCaseRunResult>> captor = ArgumentCaptor.captor();
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS).atLeast(2)).addResults(captor.capture());

        boolean foundSynthetic = captor.getAllValues().stream()
                .flatMap(List::stream)
                .anyMatch(r -> r.getExecutionStatus() == ExecutionStatus.ERROR
                        && input1.getTestCaseId().equals(r.getTestCaseId()));
        assertThat(foundSynthetic).as("synthetic ERROR row for input1").isTrue();
    }

    @Test
    @DisplayName("Long-running workers are awaited to completion with no cancellation and no timeout")
    void shouldNotTimeoutOnLongRun_whenNoCancellation() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);

        // Workers sleep past any reasonable "grace period" that used to exist — awaitCompletion() has no
        // timeout at all, so both MUST complete normally.
        EvaluationContext context =
                buildContextBuilder(1, 2).concurrencyLevel(2).build();
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenAnswer(inv -> {
            Thread.sleep(200);
            return List.of(result1);
        });
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenAnswer(inv -> {
            Thread.sleep(200);
            return List.of(result2);
        });

        runner.submit(List.of(input1, input2));
        runner.awaitCompletion();

        verify(resultsWriter).addResults(eq(List.of(result1)));
        verify(resultsWriter).addResults(eq(List.of(result2)));
    }

    @Test
    @DisplayName("Worker exception synthesizes ERROR row with expected envelope and zeroed timing")
    void shouldSynthesizeErrorRow_whenWorkerThrows() throws Exception {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(1, 2);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenThrow(new RuntimeException("boom"));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(List.of(result2));

        runner.submit(List.of(input1, input2));
        runner.awaitCompletion();

        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result2)));

        ArgumentCaptor<List<TestCaseRunResult>> captor = ArgumentCaptor.captor();
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS).atLeast(2)).addResults(captor.capture());

        TestCaseRunResult synthetic = captor.getAllValues().stream()
                .flatMap(List::stream)
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
        assertThat(envelope.get("error").get("type").asString()).isEqualTo("RuntimeException");
        assertThat(envelope.get("error").get("message").asString()).isEqualTo("boom");
        assertThat(envelope.get("error").get("origin").asString()).isEqualTo("executor");
    }

    @Test
    @DisplayName("Synthesis failure (writer throws on ERROR row) is logged and does not stop subsequent test cases")
    void shouldNotRetry_whenSynthesisFails() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context =
                buildContextBuilder(1, 2).concurrencyLevel(2).build();
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenThrow(new RuntimeException("boom"));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(List.of(result2));

        // Writer throws when handed the synthetic ERROR row for input1 (ordering may vary,
        // so match on any ERROR-status row).
        doAnswer(inv -> {
                    List<TestCaseRunResult> results = inv.getArgument(0);
                    if (results.stream().anyMatch(r -> r.getExecutionStatus() == ExecutionStatus.ERROR)) {
                        throw new RuntimeException("writer failed");
                    }
                    return null;
                })
                .when(resultsWriter)
                .addResults(anyList());

        // Must complete normally (no rethrow).
        runner.submit(List.of(input1, input2));
        runner.awaitCompletion();

        // input2's real result still attempted (even though synthesis failed)
        verify(resultsWriter, timeout(ASYNC_TIMEOUT_MS)).addResults(eq(List.of(result2)));
    }

    @Test
    @DisplayName("awaitCompletion across multiple submit calls (simulating multiple DB pages) keeps rate limiter state")
    void awaitCompletion_multipleSubmitCallsAcrossPages_reusesSameRunnerState() {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        TestCaseRunResult result1 = buildResult(input1);
        TestCaseRunResult result2 = buildResult(input2);
        EvaluationContext context = buildContext(1, 2);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(eq(input1), any(), eq(0), anyList())).thenReturn(List.of(result1));
        when(evaluationWorker.execute(eq(input2), any(), eq(0), anyList())).thenReturn(List.of(result2));

        // Simulate two separate DB pages: submit() called once per page, awaitCompletion() once at the end.
        runner.submit(List.of(input1));
        runner.submit(List.of(input2));
        runner.awaitCompletion();

        verify(evaluationWorker, times(1)).execute(eq(input1), any(EvaluationContext.class), eq(0), anyList());
        verify(evaluationWorker, times(1)).execute(eq(input2), any(EvaluationContext.class), eq(0), anyList());
        verify(resultsWriter).addResults(eq(List.of(result1)));
        verify(resultsWriter).addResults(eq(List.of(result2)));
    }

    @Test
    @DisplayName("shutdownNow interrupts in-flight workers and their results are dropped without a synthetic row")
    void shouldDropResultsAndSkipSyntheticRow_whenWorkerInterruptedByShutdownNow() throws InterruptedException {
        TestCaseRunInput input1 = buildInput();
        TestCaseRunInput input2 = buildInput();
        CountDownLatch workersStarted = new CountDownLatch(2);

        EvaluationContext context =
                buildContextBuilder(1, 2).concurrencyLevel(2).build();
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), any(Integer.class), anyList()))
                .thenAnswer(inv -> {
                    workersStarted.countDown();
                    Thread.sleep(60_000);
                    TestCaseRunInput in = inv.getArgument(0);
                    return List.of(buildResult(in));
                });

        runner.submit(List.of(input1, input2));
        assertThat(workersStarted.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        executor.shutdownNow();

        runner.awaitCompletion();

        verify(resultsWriter, never()).addResults(anyList());
    }

    @Test
    @DisplayName("submit returns false and releases the acquired permit when the executor rejects submission")
    void shouldReturnFalseAndReleasePermit_whenExecutorRejectsSubmission() {
        executor.shutdown();

        EvaluationContext context =
                buildContextBuilder(1, 2).concurrencyLevel(1).build();
        TestCaseRunner runner = createRunner(context);

        // If submit() failed to release the permit it acquired before the rejected runAsync call, a
        // second submit() on the same runner would block forever on semaphore.acquire() (concurrencyLevel
        // = 1, no permit ever released). assertTimeoutPreemptively turns that hang into a failure instead
        // of an indefinite CI stall.
        boolean secondCallAccepted = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            boolean first = runner.submit(List.of(buildInput()));
            assertThat(first).isFalse();
            return runner.submit(List.of(buildInput()));
        });

        assertThat(secondCallAccepted).isFalse();
        verify(evaluationWorker, never()).execute(any(), any(), any(Integer.class), anyList());
    }

    @Test
    @DisplayName(
            "worker wrapper drops the result and skips the synthetic row when the worker call throws after interrupting itself")
    void shouldPreserveInterruptFlagAtWrapper_whenWorkerCallThrowsAfterInterrupt() {
        TestCaseRunInput input = buildInput();
        EvaluationContext context = buildContext(1, 1);
        TestCaseRunner runner = createRunner(context);

        when(evaluationWorker.execute(any(TestCaseRunInput.class), any(), eq(0), anyList()))
                .thenAnswer(inv -> {
                    Thread.currentThread().interrupt();
                    throw new UncheckedIOException(new IOException("interrupted"));
                });

        runner.submit(List.of(input));
        runner.awaitCompletion();

        verify(resultsWriter, never()).addResults(anyList());
    }
}
