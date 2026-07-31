package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.runner.job.EvaluationContext;

/**
 * Interface for evaluation execution strategies.
 * Supports both in-process execution and future K8s Job delegation.
 */
public interface EvaluationExecutor {

    /**
     * Executes an evaluation run with the given context.
     * This method blocks until execution is complete, cancelled, or failed.
     *
     * @param context the evaluation context carrying run configuration and cancellation signal
     */
    void execute(EvaluationContext context);
}
