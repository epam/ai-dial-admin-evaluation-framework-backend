package com.epam.aidial.evaluation.runner.client.dialcore;

/**
 * Error code semantics for DIAL Core upstream failures.
 * Used by {@link DialCoreErrorMapper}; web layer maps these to {@code ErrorCode} for API responses.
 */
public enum DialCoreErrorCode {
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    NOT_FOUND,
    VALIDATION_ERROR,
    /** Upstream (Core) rejected token after we accepted it; we return 502. */
    UPSTREAM_AUTH_ERROR,
    /** Upstream (Core) reported resource not found; we return 502. */
    UPSTREAM_NOT_FOUND,
    UPSTREAM_ERROR,
    UPSTREAM_TIMEOUT
}
