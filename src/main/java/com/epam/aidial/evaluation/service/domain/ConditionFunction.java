package com.epam.aidial.evaluation.service.domain;

/**
 * SPI for a custom/system metric-execution condition function. A condition whose whole trimmed value
 * is a bare {@code name()} call (e.g. {@code isLastTurn()}) is dispatched to the {@link ConditionFunction}
 * whose {@link #name()} matches, instead of being evaluated as JSONata.
 *
 * <p>No built-in functions ship today (the registry is empty); per-turn functions like
 * {@code isLastTurn()} arrive with a future per-turn conditional-evaluation change and will read the
 * additional {@link ConditionContext} fields introduced then.
 */
public interface ConditionFunction {

    /** The function name as written in a condition, without the trailing {@code ()}. */
    String name();

    /**
     * Evaluates this function against the condition context.
     *
     * @return {@code true} to run the metric, {@code false} to skip it
     */
    boolean evaluate(ConditionContext context);
}
