package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;

/**
 * Service for JSONata expression validation and evaluation.
 *
 * <p>This is the single entry point for all JSONata operations in the codebase.
 * No other class should import from {@code com.dashjoin.jsonata} directly.
 * Swapping the JSONata library in the future = replacing the single implementation class.
 */
public interface JsonataEvaluationService {

    /**
     * Validates that the given expression is syntactically correct JSONata.
     *
     * @param expression the JSONata expression to validate (must not be blank)
     * @throws ValidationException if the expression is syntactically invalid
     */
    void validateExpression(String expression);

    /**
     * Evaluates a JSONata expression against the given JSON data string.
     *
     * @param expression the JSONata expression to evaluate
     * @param jsonData   the JSON string to evaluate against (may be null or blank)
     * @return the result of evaluation, or null if the path is missing or data is null
     * @throws ValidationException if the expression is syntactically invalid
     * @throws IllegalStateException if evaluation fails due to a non-syntax error
     */
    Object evaluate(String expression, String jsonData);
}
