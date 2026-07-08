package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single entry point for metric-execution conditions. A condition is either a bare {@code name()}
 * custom-function call (dispatched to {@link ConditionFunctionRegistry}) or a JSONata expression
 * (delegated to {@link JsonataEvaluationService}); JSONata's own functions are {@code $}-prefixed, so
 * a bare {@code name()} never collides.
 *
 * <p>{@link #validate} is used at write time (hard 400 on a malformed condition). {@link #evaluate}
 * is used at run time and never throws — it maps every outcome to a {@link ConditionDecision}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ConditionExpressionEvaluator {

    /** A whole trimmed condition matching this is a custom-function call (bare identifier + {@code ()}). */
    private static final Pattern CUSTOM_FUNCTION = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\(\\)$");

    private static final String DATA_NAMESPACE = "data";
    private static final String RESPONSE_NAMESPACE = "response";

    private final JsonataEvaluationService jsonataEvaluationService;
    private final ConditionFunctionRegistry functionRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Validates a condition at write time. No-op for a null/blank condition. Rejects (via
     * {@link ValidationException} → HTTP 400) a bare {@code name()} referencing an unregistered function,
     * or a syntactically invalid JSONata expression.
     */
    public void validate(String condition) {
        if (isBlank(condition)) {
            return;
        }
        final String trimmed = condition.trim();
        final String functionName = customFunctionName(trimmed);
        if (functionName != null) {
            if (!functionRegistry.contains(functionName)) {
                throw new ValidationException("Unknown condition function: " + functionName + "()");
            }
            return;
        }
        jsonataEvaluationService.validateExpression(trimmed);
    }

    /**
     * Evaluates a condition for a single test-case result. A blank condition always runs. Only a clean
     * boolean {@code true}/{@code false} maps to RUN/SKIP; any other result (non-boolean, null, or a
     * thrown error) maps to a surfaced ERROR. Never throws.
     */
    public ConditionDecision evaluate(String condition, ConditionContext context) {
        if (isBlank(condition)) {
            return ConditionDecision.run();
        }
        final String trimmed = condition.trim();
        try {
            final Boolean result;
            final String functionName = customFunctionName(trimmed);
            if (functionName != null) {
                final ConditionFunction function = functionRegistry.get(functionName);
                if (function == null) {
                    return ConditionDecision.error("Unknown condition function: " + functionName + "()");
                }
                result = function.evaluate(context);
            } else {
                final Object raw = jsonataEvaluationService.evaluate(trimmed, buildDictionaryJson(context));
                result = (raw instanceof Boolean bool) ? bool : null;
            }
            if (result == null) {
                return ConditionDecision.error("Condition did not evaluate to a boolean: " + trimmed);
            }
            return result ? ConditionDecision.run() : ConditionDecision.skip();
        } catch (ValidationException | IllegalStateException | JacksonException e) {
            return ConditionDecision.error("Condition evaluation failed: " + e.getMessage());
        }
    }

    private String customFunctionName(String trimmed) {
        if (!CUSTOM_FUNCTION.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.substring(0, trimmed.length() - 2);
    }

    /**
     * Serializes the context into {@code {"data": ..., "response": ...}}. Built from parsed
     * {@code JsonNode} trees and serialized as an {@code ObjectNode} so explicit JSON nulls are preserved
     * (the shared {@code NON_NULL} mapper would drop null-valued map entries, making a present-but-null
     * column look absent — see AGENTS.md JSONB-null caveat).
     */
    private String buildDictionaryJson(ConditionContext context) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.set(DATA_NAMESPACE, readTreeOrEmpty(context.dataJson()));
        root.set(RESPONSE_NAMESPACE, readTreeOrEmpty(context.responseJson()));
        return objectMapper.writeValueAsString(root);
    }

    private JsonNode readTreeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            log.warn("Failed to parse condition dictionary JSON, treating as empty: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
