package com.epam.aidial.evaluation.runner.job;

/**
 * Backend-supplied strategy (Decision 4 of the {@code inline-metric-evaluation} change's {@code design.md})
 * that lets {@link TurnLoopExecutor} score a just-built SUCCESS row's metrics against metric providers
 * before the next turn/request in the chain runs, so a metric's output can feed a later request's JSON body
 * or a later TSMD's {@code Expression} binding within the same run.
 *
 * <p>This interface, {@link InlineMetricRequest}, and {@link InlineMetricResult} are the only SPI surface
 * {@code evaluation-runner-core} exposes for this capability — the backend-only strategy implementation
 * lives outside this module (see {@code RunnerModuleConstraintsTest.mustNotDependOnTheEfBackend}), which is
 * why every type in this SPI is runner-local/JDK only.
 *
 * <p><b>The seam must be total.</b> {@code evaluate()} MUST NOT throw — not a {@link RuntimeException}, not
 * a checked exception, not an {@link InterruptedException} left unhandled. {@link TurnLoopExecutor}'s
 * existing {@code try/catch} synthesizes a {@code REQUEST_RESOLUTION_ERROR} row for any exception escaping
 * its turn loop; if an evaluator bug were allowed to throw, it would silently replace a genuine SUCCESS row
 * with a synthetic ERROR row, destroying real response data for a defect unrelated to the deployment call.
 * Implementations MUST fold every internal failure (including catching {@link InterruptedException} and
 * re-setting the thread's interrupt flag) into a failed {@link InlineMetricResult} instead.
 */
public interface InlineMetricEvaluator {

    /**
     * Evaluates every applicable TSMD against {@code request.row()}, using {@code request.accumulatedMetrics()}
     * as the {@code $_metrics} frame available to this row's TSMD bindings. Never throws — see the class
     * Javadoc's total-seam contract.
     */
    InlineMetricResult evaluate(InlineMetricRequest request);
}
