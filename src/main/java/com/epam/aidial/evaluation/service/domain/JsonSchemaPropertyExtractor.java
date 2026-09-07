package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the {@code properties} and {@code required} declarations off a JSON Schema. Shared by the
 * validators that check binding/argument coverage against a schema; both accept a schema that is
 * absent or malformed, in which case an empty set means "no usable schema — skip the check" rather
 * than "the schema declares nothing".
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonSchemaPropertyExtractor {

    private final ObjectMapper objectMapper;

    /**
     * @param schema parsed schema (may be null)
     * @return declared property names, empty when the schema is absent or declares no properties
     */
    public Set<String> propertyNames(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) {
            return Set.of();
        }
        return stringNames(properties.keySet());
    }

    /**
     * @param schemaJson JSON text of the schema (may be null or blank)
     * @return declared property names, empty when the text is absent, blank, or unparseable
     */
    public Set<String> propertyNames(String schemaJson) {
        JsonNode properties = readField(schemaJson, "properties");
        if (properties == null || !properties.isObject()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        properties.propertyNames().forEach(names::add);
        return names;
    }

    /**
     * @param schema parsed schema (may be null)
     * @return required property names, empty when the schema is absent or declares nothing required
     */
    public Set<String> requiredNames(Map<String, Object> schema) {
        if (schema == null || !(schema.get("required") instanceof Collection<?> required)) {
            return Set.of();
        }
        return stringNames(required);
    }

    /**
     * @param schemaJson JSON text of the schema (may be null or blank)
     * @return required property names, empty when the text is absent, blank, or unparseable
     */
    public Set<String> requiredNames(String schemaJson) {
        JsonNode required = readField(schemaJson, "required");
        if (required == null || !required.isArray()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        required.forEach(node -> {
            if (node.isString()) {
                names.add(node.asString());
            }
        });
        return names;
    }

    private JsonNode readField(String schemaJson, String field) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(schemaJson).get(field);
        } catch (JacksonException e) {
            // Graceful degradation: a schema we cannot read must not fail the whole validation pass.
            log.warn("Failed to parse JSON schema, skipping '{}' checks: {}", field, e.getMessage(), e);
            return null;
        }
    }

    private static Set<String> stringNames(Collection<?> values) {
        Set<String> names = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String name) {
                names.add(name);
            }
        }
        return names;
    }
}
