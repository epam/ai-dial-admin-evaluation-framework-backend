package com.epam.aidial.evaluation.runner.exception;

/**
 * Thrown when a request-template JSON body cannot be turned into a request to send: the resolved
 * template content is an unsupported type, the JSONata source fails to parse or evaluate, or the
 * evaluation result is not a JSON object.
 */
public class RequestBodyEvaluationException extends RuntimeException {

    public RequestBodyEvaluationException(String message) {
        super(message);
    }

    public RequestBodyEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
