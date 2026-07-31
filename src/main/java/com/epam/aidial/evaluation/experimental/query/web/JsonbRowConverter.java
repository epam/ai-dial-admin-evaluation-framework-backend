package com.epam.aidial.evaluation.experimental.query.web;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts a structured-query result's rows for the REST response by replacing every {@link JSONB}
 * value (how jOOQ surfaces JSONB columns) with parsed JSON, so clients receive real nested objects
 * and arrays instead of escaped JSON strings. Scalar values pass through unchanged. Field order is
 * preserved.
 *
 * <p>Read/presentation path: a value that fails to parse (corrupt stored JSON) degrades gracefully
 * to its raw text rather than failing the whole request.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonbRowConverter {

    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> toJsonRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::toJsonRow).toList();
    }

    private Map<String, Object> toJsonRow(Map<String, Object> row) {
        final Map<String, Object> converted = new LinkedHashMap<>(row.size());
        row.forEach((name, value) -> converted.put(name, toJsonValue(name, value)));
        return converted;
    }

    private Object toJsonValue(String name, Object value) {
        if (!(value instanceof JSONB jsonb)) {
            return value;
        }
        final String raw = jsonb.data();
        try {
            final JsonNode node = objectMapper.readTree(raw);
            return node == null ? null : node;
        } catch (JacksonException e) {
            log.warn("Failed to parse JSONB value of field '{}' as JSON; returning raw text: {}", name, raw, e);
            return raw;
        }
    }
}
