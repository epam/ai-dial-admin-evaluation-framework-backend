package com.epam.aidial.evaluation.service.domain;

import lombok.Builder;

/**
 * Extensible carrier for the inputs a metric execution condition is evaluated against. Holds the raw
 * JSON strings of the test-case data columns (the {@code data} namespace) and the extracted/response
 * columns (the {@code response} namespace), plus the turn position of the result row being evaluated:
 * {@code turnIndex} (0-based) and {@code totalTurns} (the planned turn count). A single-turn result is
 * {@code 0}/{@code 1}. Because a test case's turns are always contiguous {@code 0..N-1}, the last turn
 * is {@code turnIndex == totalTurns - 1}.
 *
 * <p>It also holds the chain position of the row's producing request ({@code requestIndex}, 0-based) and
 * that request's resolved label ({@code requestLabel}), exposed through the {@code request} namespace. This
 * is how a metric is targeted at one request of a multi-request suite: the metric list stays flat and
 * targeting reuses this existing per-metric {@code condition} rather than adding a second mechanism. A
 * single-request suite sees {@code request.index = 0} and its resolved default label, so behavior is
 * unchanged. Note there is deliberately no {@code request.total}/{@code request.last}: unlike turn count,
 * chain length is configuration — fixed for the run and known while writing the condition.
 *
 * <p>The carrier exists so that new inputs can be added without changing
 * {@link ConditionExpressionEvaluator#evaluate} — additive builder fields only, no signature churn.
 */
@Builder
public record ConditionContext(
        String dataJson, String responseJson, int turnIndex, int totalTurns, int requestIndex, String requestLabel) {}
