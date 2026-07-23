package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;

/**
 * Outcome of a single conversation turn after retries: the final execution status, the HTTP status code
 * (null when the call never produced a response, e.g. a timeout), the raw response body, and the number of
 * retries performed for this turn.
 */
public record TurnOutcome(ExecutionStatus status, Integer statusCode, String responseBody, int retryCount) {}
