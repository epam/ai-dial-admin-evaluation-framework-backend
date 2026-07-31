package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.exception.ValidationException;
import java.util.Map;

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

    /**
     * Evaluates a JSONata expression against the given JSON data string, with additional named
     * variable bindings available to the expression as {@code $name}.
     *
     * <p>Binding semantics: a map entry whose value is a Java {@code null} is bound as an
     * <strong>explicit JSON null</strong> (visible to {@code $exists} as present, and participating
     * in null-append semantics, e.g. {@code $append($x, [1])} on a null-bound {@code $x} yields
     * {@code [null, 1]}) — it is NOT the same as leaving the variable unbound. A name that is
     * entirely absent from the map is unbound; referencing it in the expression evaluates to
     * {@code undefined} (Java {@code null} in the result).
     *
     * <p>Callers must not put {@code com.dashjoin.*} types (e.g. the library's internal null
     * sentinel) into the bindings map — only plain Java/Jackson value types (String, Number,
     * Boolean, Map, List, null).
     *
     * <p><strong>Asymmetric root-document behavior vs. the 2-arg overload:</strong> when
     * {@code jsonData} is null or blank, the root input document ({@code $}) is an <strong>empty
     * object</strong>, not null. This overload is intended for template evaluation, where all
     * inputs the expression needs flow through named bindings and the expression body itself is
     * the authored payload — an empty-object root lets root-referencing expressions (e.g.
     * {@code $keys($)}) behave predictably instead of short-circuiting to null as the 2-arg
     * overload does.
     *
     * @param expression the JSONata expression to evaluate
     * @param jsonData   the JSON string to evaluate the expression's root document against (may be
     *                   null or blank, in which case the root document is an empty object)
     * @param bindings   named variables to bind into the expression's evaluation frame; a null
     *                   value binds an explicit JSON null, a name absent from the map is unbound
     * @return the result of evaluation
     * @throws ValidationException if the expression is syntactically invalid
     * @throws IllegalStateException if evaluation fails due to a non-syntax error (including
     *     exceeding the configured runtime bounds)
     */
    Object evaluate(String expression, String jsonData, Map<String, Object> bindings);
}
