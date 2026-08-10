package com.epam.aidial.evaluation.runner.util;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public final class TestCaseTurnsCsvSerializer {

    private static final TypeReference<List<Map<String, Object>>> TURNS_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;

    /**
     * Serializes a multi-turn data array (list of turn-data maps) to a JSON array string. Returns
     * {@code null} for a null input so an absent {@code multiTurnData} maps to a NULL column (single-turn);
     * throws (fail-fast) on serialization failure to prevent silent data loss.
     *
     * @param turns ordered list of turn-data maps (may be null)
     * @return JSON array string, or {@code null} when {@code turns} is null
     */
    public String serializeTurns(List<Map<String, Object>> turns) {
        if (turns == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(turns);
        } catch (JacksonException e) {
            log.error("Failed to serialize multi-turn data: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to serialize multi-turn data", e);
        }
    }

    /**
     * Deserializes a multi-turn data array from a JSON array string. Returns {@code null} for null/blank
     * input (absent multiTurnData) and, on a parse error, logs and returns {@code null} (graceful
     * degradation for a non-critical read path).
     *
     * @param json JSON array string (may be null or blank)
     * @return ordered list of turn-data maps, or {@code null} when absent/unparseable
     */
    public List<Map<String, Object>> deserializeTurns(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, TURNS_TYPE);
        } catch (JacksonException e) {
            log.warn("Failed to deserialize multi-turn data, returning null: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Deserializes a multi-turn data array from a JSON array string, propagating a parse failure instead
     * of swallowing it. Returns {@code null} for null/blank input (absent {@code multiTurnData}), same as
     * {@link #deserializeTurns}. Unlike that lenient method, a non-blank value that fails to parse is
     * NOT caught here — the {@link JacksonException} propagates.
     *
     * <p>Write paths that persist a re-serialized turn array (the CSV import fixup pass, dataset
     * revalidation Phase 1) need to distinguish "no stored turns" (safe to treat as single-turn) from
     * "turns present but unreadable" (must be skipped, never overwritten — writing back {@code null}
     * would silently convert a multi-turn case to single-turn and destroy every turn). {@link
     * #deserializeTurns} collapses both cases to {@code null}, which is correct for read paths but
     * destructive here; this method exists so a write path has a real exception to catch and log.
     *
     * @param json JSON array string (may be null or blank)
     * @return ordered list of turn-data maps, or {@code null} when absent
     * @throws JacksonException if {@code json} is non-blank and cannot be parsed
     */
    public List<Map<String, Object>> deserializeTurnsStrict(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return mapper.readValue(json, TURNS_TYPE);
    }
}
