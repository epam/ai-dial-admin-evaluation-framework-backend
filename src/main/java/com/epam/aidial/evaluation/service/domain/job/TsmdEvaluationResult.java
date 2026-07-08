package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import java.util.List;

/**
 * Typed carrier for a single TSMD evaluation result.
 * Every variant carries the pre-extracted output field names from the TSMD's output schema.
 */
public sealed interface TsmdEvaluationResult
        permits TsmdEvaluationResult.Success, TsmdEvaluationResult.Failure, TsmdEvaluationResult.ConditionError {

    List<String> outputFieldNames();

    /** Successful evaluation — response contains the metric output. */
    record Success(EvaluationResponseDto response, List<String> outputFieldNames) implements TsmdEvaluationResult {}

    /** Transport failure — evaluation call failed with an exception. */
    record Failure(Exception error, List<String> outputFieldNames) implements TsmdEvaluationResult {}

    /**
     * The metric's execution condition failed to evaluate (threw, or returned a non-boolean/null). The
     * metric was not dispatched; {@code message} is surfaced as a metric-level {@code metricInfos} error
     * (rendered as the {@code metricError::<name>} export column). Unlike {@link Failure}, this does NOT
     * mark the test-case result as failed.
     */
    record ConditionError(String message, List<String> outputFieldNames) implements TsmdEvaluationResult {}
}
