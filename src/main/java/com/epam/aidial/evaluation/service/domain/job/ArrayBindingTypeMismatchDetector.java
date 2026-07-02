package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Detects resolved metric bindings whose value is an array while the metric's schema declares the target
 * property as a non-array (e.g. scalar) type. This happens, for example, when a multi-step Response binding
 * (whose extracted column is a per-turn array) is bound to a scalar metric property without a
 * {@code jsonataExpression} to select a single turn. Purely diagnostic — callers log the result for
 * traceability; it does not fail evaluation.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ArrayBindingTypeMismatchDetector {

    private final ObjectMapper objectMapper;

    /**
     * Returns the names of resolved properties whose value is an array but whose schema-declared type does not
     * permit an array.
     *
     * @param schemaJson     JSON text of the metric config/input schema (may be null/blank/malformed)
     * @param resolvedValues resolved binding values keyed by metric property name
     * @return property names with an array value bound to a non-array-typed property; empty if none / unknown
     */
    public List<String> detect(String schemaJson, Map<String, Object> resolvedValues) {
        if (schemaJson == null || schemaJson.isBlank() || resolvedValues == null || resolvedValues.isEmpty()) {
            return List.of();
        }

        final JsonNode properties;
        try {
            properties = objectMapper.readTree(schemaJson).get("properties");
        } catch (JacksonException e) {
            log.warn("Failed to parse metric schema JSON, skipping array-binding type check: {}", e.getMessage(), e);
            return List.of();
        }
        if (properties == null || !properties.isObject()) {
            return List.of();
        }

        final List<String> mismatches = new ArrayList<>();
        properties.propertyNames().forEach(property -> {
            if (!(resolvedValues.get(property) instanceof List<?>)) {
                return;
            }
            final JsonNode typeNode = properties.get(property).get("type");
            if (typeNode != null && !permitsArray(typeNode)) {
                mismatches.add(property);
            }
        });
        return mismatches;
    }

    private static boolean permitsArray(JsonNode typeNode) {
        if (typeNode.isString()) {
            return "array".equals(typeNode.asString());
        }
        if (typeNode.isArray()) {
            for (JsonNode element : typeNode) {
                if (element.isString() && "array".equals(element.asString())) {
                    return true;
                }
            }
            return false;
        }
        // Unrecognised type declaration shape — do not flag.
        return true;
    }
}
