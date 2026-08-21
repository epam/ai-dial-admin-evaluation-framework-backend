package com.epam.aidial.evaluation.client.dialadas;

import lombok.Getter;

/**
 * Exception thrown when a dial-adas query-execute call fails. Mirrors McpInvocationException's shape.
 */
@Getter
public class DialAdasClientException extends RuntimeException {

    private final int statusCode;

    public DialAdasClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public DialAdasClientException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
