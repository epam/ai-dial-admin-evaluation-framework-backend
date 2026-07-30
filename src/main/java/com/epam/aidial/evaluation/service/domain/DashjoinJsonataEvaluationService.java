package com.epam.aidial.evaluation.service.domain;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Jsonata.Frame;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.JsonataProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * JSONata expression evaluation service backed by {@code com.dashjoin:jsonata:0.9.10}.
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
    private final JsonataProperties jsonataProperties;

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

        Frame frame = compiled.createFrame();
        frame.setRuntimeBounds(jsonataProperties.getEvaluationTimeoutMs(), jsonataProperties.getMaxRecursionDepth());

        try {
            Object data = objectMapper.readValue(jsonData, Object.class);
            return compiled.evaluate(data, frame);
        } catch (JException ex) {
            throw new IllegalStateException("JSONata evaluation failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to evaluate JSONata expression: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Object evaluate(String expression, String jsonData, Map<String, Object> bindings) {
        Jsonata compiled;
        try {
            compiled = jsonata(expression);
        } catch (JException ex) {
            throw new ValidationException("Invalid JSONata expression: " + ex.getMessage());
        }

        Frame frame = compiled.createFrame();
        // entrySet iteration (not Map.copyOf) because bindings may legitimately contain null
        // values, which Map.copyOf rejects.
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            Object value = entry.getValue();
            frame.bind(entry.getKey(), value != null ? value : Jsonata.NULL_VALUE);
        }
        frame.setRuntimeBounds(jsonataProperties.getEvaluationTimeoutMs(), jsonataProperties.getMaxRecursionDepth());

        try {
            Object data = (jsonData == null || jsonData.isBlank())
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(jsonData, Object.class);
            return compiled.evaluate(data, frame);
        } catch (JException ex) {
            throw new IllegalStateException("JSONata evaluation failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to evaluate JSONata expression: " + ex.getMessage(), ex);
        }
    }
}
