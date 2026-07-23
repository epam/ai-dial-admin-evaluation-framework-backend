package com.epam.aidial.evaluation.service.domain;

import lombok.Builder;

/**
 * Extensible carrier for the inputs a metric execution condition is evaluated against. Holds the raw
 * JSON strings of the test-case data columns (the {@code data} namespace) and the extracted/response
 * columns (the {@code response} namespace), plus the turn position of the result row being evaluated:
 * {@code turnIndex} (0-based) and {@code totalTurns} (the planned turn count). A single-turn result is
 * {@code 0}/{@code 1}. Because a conversation's turns are always contiguous {@code 0..N-1}, the last turn
 * is {@code turnIndex == totalTurns - 1}.
 *
 * <p>The carrier exists so that new inputs can be added without changing
 * {@link ConditionExpressionEvaluator#evaluate} — additive builder fields only, no signature churn. The
 * turn position is exposed to conditions through the {@code turn} namespace of the evaluation dictionary
 * (e.g. {@code turn.last}, {@code turn.index}, {@code turn.total}).
 */
@Builder
public record ConditionContext(String dataJson, String responseJson, int turnIndex, int totalTurns) {}
