package com.epam.aidial.evaluation.service.domain;

import lombok.Builder;

/**
 * Extensible carrier for the inputs a metric execution condition is evaluated against. Holds the raw
 * JSON strings of the test-case data columns (the {@code data} namespace) and the extracted/response
 * columns (the {@code response} namespace), plus the turn position of the result row being evaluated
 * ({@code turnIndex} 0-based, {@code totalTurns} the conversation length; a single-turn result is
 * {@code 0}/{@code 1}). The last turn of a conversation is {@code turnIndex == totalTurns - 1}.
 *
 * <p>The carrier exists so that new inputs can be added without changing
 * {@link ConditionExpressionEvaluator#evaluate} — additive builder fields only, no signature churn.
 * The turn position is exposed to conditions through the {@code turn} namespace of the evaluation
 * dictionary (e.g. {@code turn.last}, {@code turn.index}, {@code turn.total}).
 */
@Builder
public record ConditionContext(String dataJson, String responseJson, int turnIndex, int totalTurns) {}
