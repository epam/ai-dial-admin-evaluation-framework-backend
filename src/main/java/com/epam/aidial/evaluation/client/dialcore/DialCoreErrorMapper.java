package com.epam.aidial.evaluation.client.dialcore;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Maps DIAL Core upstream HTTP status codes to HTTP status and error code semantics.
 * Design: 401 → 502 (UPSTREAM_AUTH_ERROR); 403 pass-through; 404 → 502 (UPSTREAM_NOT_FOUND);
 * other 4xx → 400; 5xx → 502 (UPSTREAM_ERROR); 504 → 504 (UPSTREAM_TIMEOUT).
 * Clarifies that the failure is on the upstream side, not our service. Does not depend on web layer.
 */
public final class DialCoreErrorMapper {

    private DialCoreErrorMapper() {}

    /**
     * Resolves the HTTP status we should return to the client for the given upstream status.
     */
    public static HttpStatus toHttpStatus(HttpStatusCode upstreamStatus) {
        if (upstreamStatus == null) {
            return HttpStatus.BAD_GATEWAY;
        }
        int code = upstreamStatus.value();
        if (code == 401) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (code == 403) {
            return HttpStatus.FORBIDDEN;
        }
        if (code == 404) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (code >= 400 && code < 500) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == 504) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    /**
     * Resolves the error code semantics for the given upstream status.
     * Web layer maps {@link DialCoreErrorCode} to {@code ErrorCode} when building the response.
     */
    public static DialCoreErrorCode toDialCoreErrorCode(HttpStatusCode upstreamStatus) {
        if (upstreamStatus == null) {
            return DialCoreErrorCode.UPSTREAM_ERROR;
        }
        int code = upstreamStatus.value();
        if (code == 401) {
            return DialCoreErrorCode.UPSTREAM_AUTH_ERROR;
        }
        if (code == 403) {
            return DialCoreErrorCode.ACCESS_DENIED;
        }
        if (code == 404) {
            return DialCoreErrorCode.UPSTREAM_NOT_FOUND;
        }
        if (code >= 400 && code < 500) {
            return DialCoreErrorCode.VALIDATION_ERROR;
        }
        if (code == 504) {
            return DialCoreErrorCode.UPSTREAM_TIMEOUT;
        }
        return DialCoreErrorCode.UPSTREAM_ERROR;
    }
}
