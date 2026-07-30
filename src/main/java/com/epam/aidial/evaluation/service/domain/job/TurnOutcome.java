package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;

/**
 * Outcome of a single test-case-run turn after retries: the final execution status, the HTTP status code
 * (null when the call never produced a response, e.g. a timeout), the raw response body (an INVOCATION_ERROR
 * envelope on a thrown exception — same shape as {@code EvaluationWorker}'s), the number of retries performed
 * for this turn, and the {@code {"retryAttempts":[...]}} logDetails JSON (null when no retry occurred) —
 * mirroring {@code EvaluationWorker}'s {@code retryCount}/{@code logDetails} pair.
 */
public record TurnOutcome(
        ExecutionStatus status, Integer statusCode, String responseBody, int retryCount, String logDetails) {}
