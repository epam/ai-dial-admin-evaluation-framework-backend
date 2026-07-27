package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import java.util.List;

/**
 * Typed carrier for a single TSMD evaluation result.
 * Both variants carry the pre-extracted output field names from the TSMD's output schema.
 */
public sealed interface TsmdEvaluationResult
        permits TsmdEvaluationResult.Success, TsmdEvaluationResult.Failure, TsmdEvaluationResult.ConditionError {

    List<String> outputFieldNames();

    /** Successful evaluation — response contains the metric output. */
    record Success(EvaluationResponseDto response, List<String> outputFieldNames) implements TsmdEvaluationResult {}

    /** Transport failure — evaluation call failed with an exception. */
    record Failure(Exception error, List<String> outputFieldNames) implements TsmdEvaluationResult {}

    /**
     * The metric's {@code condition} did not cleanly evaluate to a boolean (threw / non-boolean / null).
     * The metric is skipped and surfaced as a wholesale metric-level error ({@code metricError::<name>}),
     * but the test-case result row stays SUCCESS (a broken condition is not an evaluation failure).
     */
    record ConditionError(String message, List<String> outputFieldNames) implements TsmdEvaluationResult {}
}
