package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.security.TokenPropagationHelper;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-process evaluation executor using virtual threads bounded by semaphore.
 * Pages from test_case_run_inputs when available (snapshot runs), or falls back to
 * live test cases for legacy runs. Dispatches EvaluationWorker tasks, collects results
 * into ResultBatchWriter, and handles completion/failure/cancellation.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class InProcessEvaluationExecutor implements EvaluationExecutor {

    private static final int PAGE_SIZE = 100;

    private final TestCaseRepository testCaseRepository;
    private final TestCaseRunInputRepository testCaseRunInputRepository;
    private final EvaluationWorker evaluationWorker;
    private final ResultBatchWriter resultBatchWriter;
    private final Clock clock;
    private final TestCaseRunResultFactory testCaseRunResultFactory;

    @Override
    public void execute(EvaluationContext context) {
        List<ResponseColumnDefinitionDto> responseColumns = context.getSnapshotResponseColumns();

        int concurrency = context.getConcurrencyLevel();
        log.info(
                "Starting deployment evaluation for run {}: {} test case(s), {} run(s) each, concurrency={}",
                context.getRunId(),
                context.getNumberOfTestCases(),
                context.getNumberOfRuns(),
                concurrency);
        Semaphore semaphore = new Semaphore(concurrency);
        ExecutorService executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());

        Bucket rateLimitBucket = createRateLimitBucket(context.getRateLimitRps());

        ResultBatchWriter.RunBuffer buffer = resultBatchWriter.createBuffer(
                context.getResultBatchSize(),
                context.getRunId(),
                context.getSuiteId(),
                context.getNumberOfTestCases() * context.getNumberOfRuns());

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        boolean useInputsTable = testCaseRunInputRepository.existsByRunId(context.getRunId());

        try {
            int offset = 0;
            List<TestCaseRunInput> page;
            do {
                if (context.getCancellationSignal().get()) {
                    break;
                }

                page = fetchPage(context, useInputsTable, offset);

                for (TestCaseRunInput input : page) {
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
                        // Progress is turn-granular: a runnable multiTurn advances progress by its surviving
                        // turn count, a single-turn case or a broken multiTurn (one sentinel row) by one. This
                        // matches SnapshotInputWriter's turn-based denominator so progress reaches 100%.
                        final int unitTurnCount = capturedInput.isBroken() || capturedInput.getTotalTurns() == null
                                ? 1
                                : capturedInput.getTotalTurns();
                        String token = context.getToken();

                        CompletableFuture<Void> future = CompletableFuture.runAsync(
                                TokenPropagationHelper.withTokenRunnable(token, () -> {
                                    try {
                                        List<TestCaseRunResult> results =
                                                evaluationWorker.execute(capturedInput, context, ri, responseColumns);
                                        resultBatchWriter.addResults(buffer, results, unitTurnCount);
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
                                                resultBatchWriter.addResults(buffer, List.of(synthetic), unitTurnCount);
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

                offset += PAGE_SIZE;
            } while (page.size() == PAGE_SIZE
                    && !context.getCancellationSignal().get());

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
        } catch (Exception e) {
            log.warn("Executor error for run {}: {}", context.getRunId(), e.getMessage(), e);
            try {
                resultBatchWriter.flush(buffer);
            } catch (Exception flushEx) {
                log.error("Best-effort flush failed for run {}: {}", context.getRunId(), flushEx.getMessage(), flushEx);
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        } finally {
            try {
                resultBatchWriter.flush(buffer);
            } catch (Exception e) {
                log.error("Final flush failed for run {}: {}", context.getRunId(), e.getMessage(), e);
            }
        }
    }

    private List<TestCaseRunInput> fetchPage(EvaluationContext context, boolean useInputsTable, int offset) {
        if (useInputsTable) {
            return testCaseRunInputRepository.findByRunId(context.getRunId(), offset, PAGE_SIZE);
        }
        // Legacy fallback: page from live dataset test cases, wrap as TestCaseRunInput.
        // datasetId is sourced from the snapshot's datasetRef (always populated by resolveSnapshot
        // under the version-2 snapshot model). Disabled-ids exclusion is intentionally empty here
        // because the live suite's disabledTestCaseIds was not captured at run start for legacy runs;
        // override fields are left null since per-test-case overrides no longer exist on the model.
        List<TestCase> cases = testCaseRepository.findValidByDatasetIdExcludingIds(
                context.getDatasetId(), List.of(), offset, PAGE_SIZE);
        List<TestCaseRunInput> inputs = new ArrayList<>(cases.size());
        for (TestCase tc : cases) {
            inputs.add(TestCaseRunInput.builder()
                    .runId(context.getRunId())
                    .position(offset + inputs.size())
                    .testCaseId(tc.getId())
                    .testCaseName(tc.getTestCaseName())
                    .testCaseData(tc.getData())
                    .build());
        }
        return inputs;
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
