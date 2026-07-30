package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.util.TokenPropagationHelper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs test cases concurrently against a deployment, using virtual threads bounded by a semaphore.
 * DB-free: delivers results to a {@link ResultBatchWriter}, so a standalone runner (no DB) can reuse the
 * exact same dispatch/rate-limit/cancellation logic that {@code InProcessEvaluationExecutor} uses for
 * DB-backed runs.
 *
 * <p>Session-scoped: one instance per run, created by {@link TestCaseRunnerFactory}, not a Spring bean —
 * it holds the run's {@link Semaphore}/rate-limit {@link Bucket} as instance state for the run's whole
 * lifetime, so both stay correctly bounded/paced across every {@link #submit(List)} call (e.g. once per
 * DB page from {@code InProcessEvaluationExecutor}) rather than resetting per call. Callers submit every
 * page's worth of test cases, then call {@link #awaitCompletion()} exactly once at the end.
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
        this.executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
        this.rateLimitBucket = createRateLimitBucket(context.getRateLimitRps());
    }

    public void submit(List<TestCaseRunInput> testCases) {
        try {
            for (TestCaseRunInput input : testCases) {
                for (int runIndex = 0; runIndex < context.getNumberOfRuns(); runIndex++) {
                    if (context.getCancellationSignal().get()) {
                        break;
                    }

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
                    if (context.getCancellationSignal().get()) {
                        semaphore.release();
                        break;
                    }

                    final int ri = runIndex;
                    final TestCaseRunInput capturedInput = input;

                    CompletableFuture<Void> future = CompletableFuture.runAsync(
                            TokenPropagationHelper.withTokenRunnable(token, () -> {
                                try {
                                    List<TestCaseRunResult> results =
                                            evaluationWorker.execute(capturedInput, context, ri, responseColumns);
                                    resultsWriter.addResults(results);
                                    // Intentionally broad: the worker is the last line of defense for a
                                    // single test case. Any failure (including unchecked) MUST be turned
                                    // into a synthetic ERROR row so per-case bugs are visible instead of
                                    // silently dropped. See "Broad catch is intentional" scenario.
                                } catch (Exception e) {
                                    // Cancellation-induced interruption (executor.shutdownNow()) is
                                    // NOT a per-case bug — per "No synthetic rows for unfinished
                                    // cases" spec, the case stays absent from test_case_run_results.
                                    if (e instanceof InterruptedException
                                            || Thread.currentThread().isInterrupted()) {
                                        Thread.currentThread().interrupt();
                                    } else {
                                        log.error(
                                                "Worker failed for test case {} run {}: {}",
                                                capturedInput.getTestCaseId(),
                                                ri,
                                                e.getMessage(),
                                                e);
                                        try {
                                            TestCaseRunResult synthetic = testCaseRunResultFactory.errorResult(
                                                    capturedInput, ri, e, clock.millis());
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
                            }),
                            executor);

                    futures.add(future);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Executor interrupted for run {}", context.getRunId());
            context.getCancellationSignal().set(true);
        }
    }

    public void awaitCompletion() {
        try {
            // Stop accepting new tasks; wait either unbounded (normal) or grace-bounded (cancelled).
            executor.shutdown();
            if (!context.getCancellationSignal().get()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .join();
            } else {
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(context.getCancellationGracePeriodMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    // Grace expired with workers still alive — fall through to shutdownNow below.
                }
            }

            if (context.getCancellationSignal().get() && futures.stream().anyMatch(f -> !f.isDone())) {
                executor.shutdownNow();
                long unfinished = futures.stream().filter(f -> !f.isDone()).count();
                log.warn(
                        "Run {} cancelled with {} test case(s) interrupted before completion",
                        context.getRunId(),
                        unfinished);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Executor interrupted for run {}", context.getRunId());
            context.getCancellationSignal().set(true);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
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
