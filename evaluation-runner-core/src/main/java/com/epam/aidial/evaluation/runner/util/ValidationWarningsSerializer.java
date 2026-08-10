package com.epam.aidial.evaluation.runner.util;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.dto.analytics.ExtractionWarningDto;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializer for validation warnings and generic JSON maps.
 *
 * <p>Serialization uses fail-fast approach (throws exception on failure) to prevent data loss.
 * Deserialization uses graceful degradation (logs error, returns empty) because:
 * <ul>
 *   <li>Validation warnings are regenerable via revalidation</li>
 *   <li>Crashing on corrupted historical data provides worse UX</li>
 *   <li>The error is logged for debugging/monitoring</li>
 * </ul>
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ValidationWarningsSerializer {

    private static final TypeReference<List<ValidationWarningDto>> WARNINGS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ExtractionWarningDto>> EXTRACTION_WARNINGS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * Serializes validation warnings to JSON string.
     * Throws exception on failure to prevent silent data loss.
     *
     * @param warnings list of warnings (may be null)
     * @return JSON array string, never null (returns "[]" for null/empty input)
     * @throws IllegalStateException if serialization fails
     */
    public String serializeWarnings(List<ValidationWarningDto> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JacksonException e) {
            log.error("Failed to serialize validation warnings: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to serialize validation warnings", e);
        }
    }

    /**
     * Deserializes validation warnings from JSON string.
     * Returns empty list on failure (graceful degradation) because warnings are regenerable.
     *
     * @param json JSON array string (may be null or blank)
     * @return list of warnings, never null (returns empty list on null/blank/error)
     */
    public List<ValidationWarningDto> deserializeWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ValidationWarningDto> result = objectMapper.readValue(json, WARNINGS_TYPE);
            return result != null ? result : List.of();
        } catch (JacksonException e) {
            log.warn(
                    "Failed to deserialize validation warnings, returning empty list. "
                            + "Data can be regenerated via revalidation. Error: {}",
                    e.getMessage(),
                    e);
            return List.of();
        }
    }

    /**
     * Serializes extraction warnings to JSON string.
     * Throws exception on failure to prevent silent data loss.
     *
     * @param warnings list of extraction warnings (may be null)
     * @return JSON array string, never null (returns "[]" for null/empty input)
     * @throws IllegalStateException if serialization fails
     */
    public String serializeExtractionWarnings(List<ExtractionWarningDto> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JacksonException e) {
            log.error("Failed to serialize extraction warnings: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to serialize extraction warnings", e);
        }
    }

    /**
     * Deserializes extraction warnings from JSON string.
     * Returns empty list on failure (graceful degradation).
     *
     * @param json JSON array string (may be null or blank)
     * @return list of extraction warnings, never null (returns empty list on null/blank/error)
     */
    public List<ExtractionWarningDto> deserializeExtractionWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ExtractionWarningDto> result = objectMapper.readValue(json, EXTRACTION_WARNINGS_TYPE);
            return result != null ? result : List.of();
        } catch (JacksonException e) {
            log.warn("Failed to deserialize extraction warnings, returning empty list. Error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Serializes a map to JSON string.
     * Throws exception on failure to prevent silent data loss.
     *
     * @param map map to serialize (may be null)
     * @return JSON object string, never null (returns "{}" for null/empty input)
     * @throws IllegalArgumentException if serialization fails
     */
    public String serializeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JacksonException e) {
            log.error("Failed to serialize map: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to serialize map", e);
        }
    }

    /**
     * Deserializes a JSON string to map.
     * Returns empty map on failure (graceful degradation for non-critical reads).
     *
     * <p><strong>Note:</strong> For critical user data (parameters, facts), consider using
     * {@link com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper} methods which throw on failure.
     *
     * @param json JSON object string (may be null or blank)
     * @return map, never null (returns empty map on null/blank/error)
     */
    public Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, MAP_TYPE);
            return result != null ? result : Map.of();
        } catch (JacksonException e) {
            log.warn("Failed to deserialize JSON map, returning empty: {}", e.getMessage(), e);
            return Map.of();
        }
    }
}
