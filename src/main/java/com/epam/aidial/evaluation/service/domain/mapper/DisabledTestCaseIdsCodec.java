package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Single owner of the JSONB round-trip for a suite's {@code disabledTestCaseIds} column (a JSON array of
 * stringified UUIDs). Deserialisation is graceful (a malformed payload logs and yields an empty list so a
 * single corrupt row cannot brick run creation or the snapshot); serialisation is fail-fast (a write error
 * is a data-integrity bug). Reused by {@code TestSuiteMapper}, {@code TestSuiteRunService}, and
 * {@code SnapshotInputWriter} instead of each re-implementing the same parse loop.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class DisabledTestCaseIdsCodec {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * Parses the JSONB array of stringified UUIDs into a typed list. Returns an empty list for a
     * null/blank/malformed payload; individual non-UUID entries are skipped (logged).
     */
    public List<UUID> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            final List<String> raw = objectMapper.readValue(json, STRING_LIST_TYPE);
            final List<UUID> ids = new ArrayList<>(raw.size());
            for (String s : raw) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(s));
                } catch (IllegalArgumentException ex) {
                    log.warn("Skipping malformed UUID in disabledTestCaseIds: {}", s, ex);
                }
            }
            return ids;
        } catch (JacksonException ex) {
            log.warn("Failed to deserialize disabledTestCaseIds JSON: {}", ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Serialises a typed list of UUIDs to a JSONB-ready JSON array of stringified UUIDs. Returns
     * {@code "[]"} for null/empty input so the DB column is always non-null (matches the migration DEFAULT).
     */
    public String serialize(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        try {
            final List<String> raw = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                if (id != null) {
                    raw.add(id.toString());
                }
            }
            return objectMapper.writeValueAsString(raw);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize disabledTestCaseIds", ex);
        }
    }
}
