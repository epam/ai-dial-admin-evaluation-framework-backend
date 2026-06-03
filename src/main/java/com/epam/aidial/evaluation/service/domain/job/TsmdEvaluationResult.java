package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import java.util.List;

/**
 * Typed carrier for a single TSMD evaluation result.
 * Both variants carry the pre-extracted output field names from the TSMD's output schema.
 */
public sealed interface TsmdEvaluationResult permits TsmdEvaluationResult.Success, TsmdEvaluationResult.Failure {

    List<String> outputFieldNames();

    /** Successful evaluation — response contains the metric output. */
    record Success(EvaluationResponseDto response, List<String> outputFieldNames) implements TsmdEvaluationResult {}

    /** Transport failure — evaluation call failed with an exception. */
    record Failure(Exception error, List<String> outputFieldNames) implements TsmdEvaluationResult {}
}
