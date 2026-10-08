package com.epam.aidial.evaluation.client.dialcore;

/**
 * Thrown when {@link DialRouteTriggerClient#triggerEvalRun} fails to successfully initiate the
 * Application Route call to DIAL Core (connection failure, timeout, or a non-2xx response such as a
 * 403 when the caller's JWT is rejected). Does NOT cover failures during SSE-stream consumption after
 * a successful initial dispatch — those are handled separately via {@code ActiveRunRegistry}'s
 * disconnect-driven cancellation (see docs/patterns/dial-app-mode.md).
 */
public class DialRouteTriggerException extends RuntimeException {

    public DialRouteTriggerException(String message, Throwable cause) {
        super(message, cause);
    }
}
