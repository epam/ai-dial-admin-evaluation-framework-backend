package com.epam.aidial.evaluation.service.domain;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * JSONata expression evaluation service backed by {@code com.dashjoin:jsonata:0.9.9}.
 *
 * <p>This is the ONLY class in the codebase that imports from {@code com.dashjoin.jsonata}.
 * All library-specific exceptions are caught and re-thrown as domain exceptions.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class DashjoinJsonataEvaluationService implements JsonataEvaluationService {

    private final ObjectMapper objectMapper;

    // Compilation (parsing an expression into an AST) is the expensive step and is safe to reuse. A dashjoin
    // Jsonata instance, however, holds mutable per-evaluation state (input/timestamp/errors), so a cached
    // instance is NOT thread-safe to evaluate concurrently — evaluate() below synchronizes on the instance.
    private final Map<String, Jsonata> compiledCache = new ConcurrentHashMap<>();

    @Override
    public void validateExpression(String expression) {
        compile(expression);
    }

    @Override
    public Object evaluate(String expression, String jsonData) {
        Jsonata compiled = compile(expression);

        if (jsonData == null || jsonData.isBlank()) {
            return null;
        }

        try {
            Object data = objectMapper.readValue(jsonData, Object.class);
            synchronized (compiled) {
                return compiled.evaluate(data);
            }
        } catch (JException ex) {
            throw new IllegalStateException("JSONata evaluation failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to evaluate JSONata expression: " + ex.getMessage(), ex);
        }
    }

    private Jsonata compile(String expression) {
        try {
            return compiledCache.computeIfAbsent(expression, Jsonata::jsonata);
        } catch (JException ex) {
            throw new ValidationException("Invalid JSONata expression: " + ex.getMessage());
        }
    }
}
