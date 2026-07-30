package com.epam.aidial.evaluation.service.domain.exception;

/**
 * EF backend alias for {@link com.epam.aidial.evaluation.runner.exception.ValidationException}; extends
 * the runner module's base class so that
 * handlers catching the runner type also catch the EF backend subclass without requiring all 80+ import
 * sites to migrate to the runner package.
 */
public class ValidationException extends com.epam.aidial.evaluation.runner.exception.ValidationException {

    public ValidationException(String message) {
        super(message);
    }
}
