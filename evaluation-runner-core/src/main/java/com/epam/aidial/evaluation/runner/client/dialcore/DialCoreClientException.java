package com.epam.aidial.evaluation.runner.client.dialcore;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/**
 * Exception thrown when a call to DIAL Core API fails.
 * Carries the upstream HTTP status for mapping to appropriate client response.
 */
@Getter
public class DialCoreClientException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    public DialCoreClientException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = null;
    }

    public DialCoreClientException(HttpStatusCode statusCode, String message, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public DialCoreClientException(HttpStatusCode statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = null;
    }
}
