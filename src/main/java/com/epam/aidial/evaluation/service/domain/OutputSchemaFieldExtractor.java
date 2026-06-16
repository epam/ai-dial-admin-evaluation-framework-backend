package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts output field names from a metric's output schema JSON string.
 * Returns an empty list for null, blank, malformed, or schema-without-properties inputs.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class OutputSchemaFieldExtractor {

    private final ObjectMapper objectMapper;

    /**
     * Extracts field names from {@code output_schema.properties}.
     *
     * @param outputSchema JSON string of the output schema (may be null)
     * @return list of field names; empty list if schema is null, blank, malformed,
     *         or has no valid {@code properties} object
     */
    public List<String> extractFieldNames(String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode schema = objectMapper.readTree(outputSchema);
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<>();
            properties.propertyNames().forEach(names::add);
            return names;
        } catch (JacksonException e) {
            log.warn("Failed to parse output schema JSON, returning empty field list: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
