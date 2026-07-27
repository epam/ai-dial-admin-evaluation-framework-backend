package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;

/**
 * Outcome of a single test-case-run turn after retries: the final execution status, the HTTP status code
 * (null when the call never produced a response, e.g. a timeout), the raw response body, and the number of
 * retries performed for this turn.
 *
 * @param issued whether the HTTP call was actually sent. False only when run cancellation interrupted the
 *     rate-limit token wait, so nothing left the process. Callers deciding whether a turn deserves a result
 *     row MUST use this rather than a non-null outcome or {@code status != SUCCESS}: a not-issued turn also
 *     carries {@code ERROR} with a null status code, which is otherwise indistinguishable from a network
 *     failure that really did attempt a call.
 */
public record TurnOutcome(
        ExecutionStatus status, Integer statusCode, String responseBody, int retryCount, boolean issued) {

    /** An outcome for a call that was actually sent — every path except an interrupted token wait. */
    public static TurnOutcome issued(ExecutionStatus status, Integer statusCode, String responseBody, int retryCount) {
        return new TurnOutcome(status, statusCode, responseBody, retryCount, true);
    }

    /**
     * A call that was never sent because run cancellation interrupted its rate-limit token wait. The body
     * carries the shared CANCELLED envelope so the interruption stays diagnosable if a row is ever written.
     */
    public static TurnOutcome notIssued(String responseBody) {
        return new TurnOutcome(ExecutionStatus.ERROR, null, responseBody, 0, false);
    }
}
