package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;

/**
 * Outcome of a single test-case-run turn after retries: the final execution status, the HTTP status code
 * (null when the call never produced a response, e.g. a timeout), the raw response body, and the number of
 * retries performed for this turn.
 */
public record TurnOutcome(ExecutionStatus status, Integer statusCode, String responseBody, int retryCount) {}
