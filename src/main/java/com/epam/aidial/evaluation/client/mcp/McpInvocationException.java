package com.epam.aidial.evaluation.client.mcp;

import lombok.Getter;

/**
 * Exception thrown when MCP tool invocation fails.
 * Mirrors DialCoreClientException pattern.
 */
@Getter
public class McpInvocationException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public McpInvocationException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public McpInvocationException(int statusCode, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }
}
