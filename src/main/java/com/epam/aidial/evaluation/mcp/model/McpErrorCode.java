package com.epam.aidial.evaluation.mcp.model;

/**
 * Stable, machine-readable error codes for the MCP structured tool error contract. See
 * {@code openspec/changes/add-mcp-server/specs/mcp-server/spec.md} ("Structured tool error
 * contract"). Every code except {@link #NOT_SUPPORTED} has a same-named counterpart in
 * {@code web.handler.ErrorCode} and carries the same meaning (verified by
 * {@code McpErrorCodeAlignmentTest}); {@link #NOT_SUPPORTED} is MCP-only — it has no REST
 * equivalent because REST does not yet expose the forward-compatible fields this rejects.
 */
public enum McpErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_OPERATION,
    ACCESS_DENIED,
    VERSION_CONFLICT,
    UNIQUE_CONSTRAINT_VIOLATION,
    TOO_MANY_REQUESTS,
    RUN_NOT_TERMINAL,
    SUITE_HAS_NO_DATASET,
    PAYLOAD_TOO_LARGE,
    UPSTREAM_ERROR,
    UPSTREAM_TIMEOUT,
    UPSTREAM_AUTH_ERROR,
    NOT_SUPPORTED,
    INTERNAL_ERROR
}
