package com.epam.aidial.evaluation.web.handler;

/**
 * Standard machine-readable error codes for API error responses.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    INVALID_FILTER,
    INVALID_SORT,
    INVALID_SCHEMA,
    CSV_PARSE_ERROR,
    CSV_EMPTY,
    CSV_TOO_LARGE,
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    NOT_FOUND,
    VERSION_CONFLICT,
    UNIQUE_CONSTRAINT_VIOLATION,
    TOO_MANY_REQUESTS,
    PAYLOAD_TOO_LARGE,
    INVALID_OPERATION,
    SNAPSHOT_SUITE_MISSING,
    SNAPSHOT_DATASET_MISSING,
    UNSUPPORTED_SNAPSHOT_VERSION,
    RUN_NOT_TERMINAL,
    /** POST /datasets with visibility=PRIVATE but no bindToSuiteId. */
    PRIVATE_DATASET_REQUIRES_SUITE_BINDING,
    /** POST /datasets with visibility=PUBLIC but bindToSuiteId supplied. */
    PUBLIC_DATASET_FORBIDS_SUITE_BINDING,
    /** Attempt to bind a second suite to a PRIVATE dataset (app-level pre-check, or the DB trigger on a race). */
    PRIVATE_DATASET_ALREADY_BOUND,
    /** PATCH /test-suites/{id} attempted to change datasetId (incl. to null) on a suite currently bound to a PRIVATE dataset. */
    PRIVATE_DATASET_REBIND_FORBIDDEN,
    /** PATCH /datasets/{id}/visibility PUBLIC→PRIVATE rejected because binding count is not exactly 1. */
    PRIVATE_TRANSITION_INVALID_BINDING_COUNT,
    /** POST /test-suites/{id}/runs on a suite with datasetId=null. */
    SUITE_HAS_NO_DATASET,
    INTERNAL_ERROR,
    /** Upstream (Core) rejected token after we accepted it; we return 502. */
    UPSTREAM_AUTH_ERROR,
    /** Upstream (Core) reported resource not found; we return 502. */
    UPSTREAM_NOT_FOUND,
    /** Upstream service (e.g. DIAL Core) returned an error or was unreachable; we return 502. */
    UPSTREAM_ERROR,
    /** Upstream service did not respond in time; we return 504. */
    UPSTREAM_TIMEOUT
}
