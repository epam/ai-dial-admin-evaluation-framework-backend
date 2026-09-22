package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.runner.exception.ValidationException;

/**
 * Isolates the import of {@code runner.exception.ValidationException} for
 * {@link McpToolErrorTranslatorTest}. That test also imports the EF backend subclass {@code
 * service.domain.exception.ValidationException} to exercise polymorphic dispatch through {@link
 * McpToolErrorTranslator}, and Java has no import aliasing to disambiguate two classes sharing a
 * simple name in one file. Delegating construction here, returning the runner base type, lets the
 * test reference it without an FQN.
 */
final class RunnerValidationExceptionFixture {

    private RunnerValidationExceptionFixture() {}

    static RuntimeException runnerValidationException(String message) {
        return new ValidationException(message);
    }
}
