package com.epam.aidial.evaluation.service.domain.job;

/**
 * Phase-3 metric-score computation, triggered by the run job after metric evaluation. Defined in the
 * stable {@code service} layer so {@link TestSuiteEvaluationJob} can trigger the computation without
 * depending on its implementation — the implementation lives in the experimental query package
 * (it runs persisted definition queries through the query DSL) and is wired in by Spring. This
 * inversion keeps the {@code service → experimental.query.service} dependency out of the bytecode (see
 * {@code LayeredArchitectureTest}).
 */
public interface MetricScoreComputation {

    /** Computes and persists the run's metric scores for the computation described by {@code ctx}. */
    void execute(MetricScoreComputationContext ctx);
}
