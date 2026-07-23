package com.epam.aidial.evaluation.service.domain;

/**
 * Outcome of evaluating a metric-execution condition for a single test-case result.
 *
 * <ul>
 *   <li>{@link Outcome#RUN} — clean boolean {@code true}: evaluate the metric normally.</li>
 *   <li>{@link Outcome#SKIP} — clean boolean {@code false}: skip and omit the metric entirely.</li>
 *   <li>{@link Outcome#ERROR} — condition threw / returned a non-boolean / null: skip the metric and
 *       surface {@link #errorMessage()} as a metric-level error (does not fail the test-case result).</li>
 * </ul>
 */
public record ConditionDecision(Outcome outcome, String errorMessage) {

    public enum Outcome {
        RUN,
        SKIP,
        ERROR
    }

    public static ConditionDecision run() {
        return new ConditionDecision(Outcome.RUN, null);
    }

    public static ConditionDecision skip() {
        return new ConditionDecision(Outcome.SKIP, null);
    }

    public static ConditionDecision error(String errorMessage) {
        return new ConditionDecision(Outcome.ERROR, errorMessage);
    }

    public boolean isRun() {
        return outcome == Outcome.RUN;
    }

    public boolean isSkip() {
        return outcome == Outcome.SKIP;
    }

    public boolean isError() {
        return outcome == Outcome.ERROR;
    }
}
