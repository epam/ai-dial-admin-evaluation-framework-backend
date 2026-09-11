package com.epam.aidial.evaluation.runner.exception;

/** Thrown when a resolved request body violates a pre-invocation validation rule. */
public class RequestBodyValidationException extends RuntimeException {

    public RequestBodyValidationException(String message) {
        super(message);
    }
}
