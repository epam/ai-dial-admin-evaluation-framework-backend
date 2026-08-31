package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a fresh {@link InlineMetricEvaluatorImpl} per run — the same shape as
 * {@link PostgresResultBatchWriterFactory} and {@code TestCaseRunnerFactory}: {@code
 * TestSuiteEvaluationJob} gains one factory dependency instead of wiring the impl's collaborators
 * itself.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class InlineMetricEvaluatorFactory {

    private final MetricRowEvaluator metricRowEvaluator;
    private final EvalSummaryBatchWriteClient evalSummaryBatchWriteClient;
    private final ObjectMapper objectMapper;

    /**
     * Builds the run's inline evaluator: provider semaphores sized from {@code context}'s
     * per-provider concurrency (built once, reused for every {@code evaluate()} call this run makes)
     * and a dedicated virtual-thread executor. {@code context.getCancellationSignal()} is reused as-is
     * — never a fresh {@code AtomicBoolean} — so Stop can still interrupt an in-flight inline
     * evaluation (design.md Decision 5).
     */
    public InlineMetricEvaluatorImpl create(MetricEvaluationContext context) {
        Map<String, Semaphore> providerSemaphores = metricRowEvaluator.buildProviderSemaphores(context);
        ExecutorService executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
        return new InlineMetricEvaluatorImpl(
                context, metricRowEvaluator, evalSummaryBatchWriteClient, objectMapper, providerSemaphores, executor);
    }
}
