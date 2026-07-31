package com.epam.aidial.evaluation.runner.util;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Small, stateless <b>failure-tolerant</b> JSON facade over the shared {@link ObjectMapper}: callers depend
 * on this narrow helper instead of injecting the raw mapper. It offers the two swallow-on-failure patterns
 * (serialize-or-{@code toString()}, parse-or-empty) plus thin node-construction/tree-parse delegations.
 *
 * <p>These methods intentionally swallow errors (callers persist best-effort data). For strict serialization
 * that must fail loudly, use {@code service.domain.mapper.JacksonMapper} (throws) instead.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class QuietJsonService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /** Serializes {@code value} to JSON; returns {@code null} for a null value and falls back to
     * {@code value.toString()} if serialization fails. */
    public String writeOrToString(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return value.toString();
        }
    }

    /** Parses a JSON object into a {@code Map}; returns an empty map for blank input or when parsing fails
     * (the failure is logged). */
    public Map<String, Object> readMapOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException e) {
            log.warn("Failed to parse JSON to map: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /** Parses JSON into a tree; returns an empty object node for blank input or when parsing fails (logged),
     * so callers can navigate with {@code path(...)} without handling exceptions. */
    public JsonNode readTreeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            log.warn("Failed to parse JSON tree: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
        }
    }

    public ObjectNode createObjectNode() {
        return objectMapper.createObjectNode();
    }

    public ArrayNode createArrayNode() {
        return objectMapper.createArrayNode();
    }
}
