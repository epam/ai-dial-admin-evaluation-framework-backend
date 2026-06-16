package com.epam.aidial.evaluation.service.domain.job;

/**
 * Interface for metric evaluation execution strategies.
 * Supports both in-process execution and future K8s Job delegation.
 */
public interface MetricEvaluationExecutor {

    /**
     * Executes metric evaluation with the given context.
     * This method blocks until execution is complete, cancelled, or failed.
     * Implementations are responsible for RunMetricSnapshot capture and
     * EvalSummary batch writing.
     *
     * @param context the metric evaluation context carrying TSMDs,
     *                cancellation signal, and retry config
     */
    void execute(MetricEvaluationContext context);
}
