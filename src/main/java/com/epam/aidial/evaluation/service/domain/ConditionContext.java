package com.epam.aidial.evaluation.service.domain;

import lombok.Builder;

/**
 * Extensible carrier for the inputs a metric execution condition is evaluated against. Holds the raw
 * JSON strings of the test-case data columns (the {@code data} namespace) and the extracted/response
 * columns (the {@code response} namespace).
 *
 * <p>The carrier exists so that new inputs (e.g. per-turn {@code stepIndex}/{@code stepCount} for a
 * future per-turn conditional evaluation) can be added without changing
 * {@link ConditionExpressionEvaluator#evaluate} or {@link ConditionFunction#evaluate} — additive
 * builder fields only, no signature churn for callers or custom-function implementors.
 */
@Builder
public record ConditionContext(String dataJson, String responseJson) {}
