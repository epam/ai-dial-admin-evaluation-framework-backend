package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.util.TokenPropagationHelper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Runs test cases concurrently against a deployment, using virtual threads bounded by a semaphore.
 * DB-free: delivers results to a {@link ResultBatchWriter}, so a standalone runner (no DB) can reuse the
 * exact same dispatch/rate-limit logic that {@code InProcessEvaluationExecutor} uses for DB-backed runs.
 *
 * <p>Session-scoped: one instance per run, created by {@link TestCaseRunnerFactory}, not a Spring bean —
 * it holds the run's {@link Semaphore}/rate-limit {@link Bucket} as instance state for the run's whole
 * lifetime, so both stay correctly bounded/paced across every {@link #submit(List)} call (e.g. once per
 * DB page from {@code InProcessEvaluationExecutor}) rather than resetting per call. Callers submit every
 * page's worth of test cases, then call {@link #awaitCompletion()} exactly once at the end.
 *
 * <p>The run's worker executor ({@link EvaluationContext#getExecutor()}) is owned by the caller: this
 * class never creates or shuts it down. Cancellation is delivered by the owner calling {@code
 * shutdownNow()} on that executor — {@link #submit(List)} then reports rejection via its {@code boolean}
 * return, and {@link #awaitCompletion()} observes the resulting worker interruptions.
 */
@Slf4j
public class TestCaseRunner {

    private final EvaluationWorker evaluationWorker;
    private final TestCaseRunResultFactory testCaseRunResultFactory;
    private final Clock clock;
    private final EvaluationContext context;
    private final List<ResponseColumnDefinitionDto> responseColumns;
    private final ResultBatchWriter resultsWriter;
    private final String token;

    private final Semaphore semaphore;
    private final ExecutorService executor;
    private final Bucket rateLimitBucket;
    private final List<CompletableFuture<Void>> futures = new ArrayList<>();
    private final AtomicInteger interruptedTasks = new AtomicInteger();

    TestCaseRunner(
            EvaluationWorker evaluationWorker,
            TestCaseRunResultFactory testCaseRunResultFactory,
            Clock clock,
            EvaluationContext context,
            List<ResponseColumnDefinitionDto> responseColumns,
            ResultBatchWriter resultsWriter) {
        this.evaluationWorker = evaluationWorker;
        this.testCaseRunResultFactory = testCaseRunResultFactory;
        this.clock = clock;
        this.context = context;
        this.responseColumns = responseColumns;
        this.resultsWriter = resultsWriter;
        this.token = context.getToken();
        this.semaphore = new Semaphore(context.getConcurrencyLevel());
        this.executor = context.getExecutor();
        this.rateLimitBucket = createRateLimitBucket(context.getRateLimitRps());
    }

    /**
     * Submits every run of every test case in {@code testCases} to the run's executor. Returns {@code
     * false} as soon as the executor rejects a submission (the run's executor was shut down by a cancel
     * request) — the caller SHALL stop fetching further pages. Returns {@code true} when every submission
     * in this page was accepted.
     */
    public boolean submit(List<TestCaseRunInput> testCases) {
        try {
            for (TestCaseRunInput input : testCases) {
                for (int runIndex = 0; runIndex < context.getNumberOfRuns(); runIndex++) {
                    log.debug(
                            "Run {}: evaluating test case {} (name={}), run {}/{}",
                            context.getRunId(),
                            input.getTestCaseId(),
                            input.getTestCaseName(),
                            runIndex + 1,
                            context.getNumberOfRuns());

                    if (rateLimitBucket != null) {
                        rateLimitBucket.asBlocking().consume(1);
                    }

                    semaphore.acquire();

                    final int ri = runIndex;
                    final TestCaseRunInput capturedInput = input;

                    try {
                        CompletableFuture<Void> future = CompletableFuture.runAsync(
                                TokenPropagationHelper.withTokenRunnable(token, () -> runWorker(capturedInput, ri)),
                                executor);
                        futures.add(future);
                    } catch (RejectedExecutionException e) {
                        semaphore.release();
                        log.debug(
                                "Run {}: executor rejected submission for test case {} run {}: {}",
                                context.getRunId(),
                                capturedInput.getTestCaseId(),
                                ri,
                                e.getMessage(),
                                e);
                        return false;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Run {}: interrupted while submitting test cases", context.getRunId(), e);
            return false;
        }
        return true;
    }

    /**
     * Runs a single test-case-run on a worker thread. Intentionally broad: the worker is the last line of
     * defense for a single test case. Any failure (including unchecked) MUST be turned into a synthetic
     * ERROR row so per-case bugs are visible instead of silently dropped — UNLESS the failure is (or
     * wraps, or coincides with) an interruption from the run's executor being shut down, in which case the
     * case stays absent from {@code test_case_run_results} per "No synthetic rows for unfinished cases".
     */
    private void runWorker(TestCaseRunInput capturedInput, int ri) {
        try {
            List<TestCaseRunResult> results = evaluationWorker.execute(capturedInput, context, ri, responseColumns);
            if (Thread.currentThread().isInterrupted()) {
                interruptedTasks.incrementAndGet();
                return;
            }
            resultsWriter.addResults(results);
        } catch (Exception e) {
            if (e instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()
                    || ExceptionUtils.indexOfType(e, InterruptedException.class) >= 0) {
                Thread.currentThread().interrupt();
                interruptedTasks.incrementAndGet();
            } else {
                log.error(
                        "Worker failed for test case {} run {}: {}",
                        capturedInput.getTestCaseId(),
                        ri,
                        e.getMessage(),
                        e);
                try {
                    TestCaseRunResult synthetic =
                            testCaseRunResultFactory.errorResult(capturedInput, ri, e, clock.millis());
                    resultsWriter.addResults(List.of(synthetic));
                } catch (Exception synthEx) {
                    log.error(
                            "Failed to record synthetic ERROR for test case {} run {}: {}",
                            capturedInput.getTestCaseId(),
                            ri,
                            synthEx.getMessage(),
                            synthEx);
                }
            }
        } finally {
            semaphore.release();
        }
    }

    /**
     * Waits for every submitted future to terminate — normally, or via interruption once the run's
     * executor has been shut down by a cancel request. There is no timeout: a long-running uncancelled run
     * is awaited unconditionally.
     */
    public void awaitCompletion() {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Run " + context.getRunId() + " await interrupted");
        } catch (ExecutionException e) {
            // Workers swallow every exception in runWorker's catch block, so a future should never
            // complete exceptionally. Surfacing this as an IllegalStateException guards against a bug
            // silently regressing that invariant.
            throw new IllegalStateException("Unexpected worker failure for run " + context.getRunId(), e);
        }

        if (interruptedTasks.get() > 0) {
            log.warn(
                    "Run {} cancelled with {} test case(s) interrupted before completion",
                    context.getRunId(),
                    interruptedTasks.get());
        }
    }

    private static Bucket createRateLimitBucket(Double rateLimitRps) {
        if (rateLimitRps == null || rateLimitRps <= 0) {
            return null;
        }
        long tokens = Math.max(1, Math.round(rateLimitRps));
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(tokens)
                .refillGreedy(tokens, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
