package com.epam.aidial.evaluation.runner.service;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
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

    @Override
    public void validateExpression(String expression) {
        try {
            jsonata(expression);
        } catch (JException ex) {
            throw new ValidationException("Invalid JSONata expression: " + ex.getMessage());
        }
    }

    @Override
    public Object evaluate(String expression, String jsonData) {
        Jsonata compiled;
        try {
            compiled = jsonata(expression);
        } catch (JException ex) {
            throw new ValidationException("Invalid JSONata expression: " + ex.getMessage());
        }

        if (jsonData == null || jsonData.isBlank()) {
            return null;
        }

        try {
            Object data = objectMapper.readValue(jsonData, Object.class);
            return compiled.evaluate(data);
        } catch (JException ex) {
            throw new IllegalStateException("JSONata evaluation failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to evaluate JSONata expression: " + ex.getMessage(), ex);
        }
    }
}
