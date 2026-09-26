package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns one {@link TestCase} into its ordered export rows: one row per turn for a multi-turn case (the
 * case's shared {@code data} merged into every turn, per-turn values winning), or a single row with a blank
 * turn index for a single-turn case. Shared by {@code CsvExportService} and ZIP export so both exporters
 * multiply multi-turn cases identically.
 *
 * <p>Lives in {@code service.domain.csv}, not {@code service.domain.zip}: {@code CsvExportService} depends
 * on it, and the CSV package must not depend on the ZIP package.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseExportRowProjector {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> TURNS_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * Projects a test case into its ordered export rows.
     *
     * @return for a multi-turn case, one row per turn (turn index {@code "0"}, {@code "1"}, …, sharedData
     *     merged into every turn); for a single-turn case, exactly one row with a blank turn index
     */
    public List<ProjectedRow> project(TestCase testCase) {
        Map<String, Object> sharedData = parseJsonToMap(testCase.getData());
        if (testCase.getMultiTurnData() != null) {
            List<Map<String, Object>> turns = parseTurns(testCase.getMultiTurnData());
            List<ProjectedRow> rows = new ArrayList<>();
            for (int i = 0; i < turns.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>(sharedData);
                row.putAll(turns.get(i));
                rows.add(new ProjectedRow(String.valueOf(i), row));
            }
            return rows;
        }
        return List.of(new ProjectedRow("", sharedData));
    }

    private List<Map<String, Object>> parseTurns(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> turns = objectMapper.readValue(json, TURNS_TYPE);
            return turns != null ? turns : List.of();
        } catch (JacksonException e) {
            log.warn("Failed to parse multiTurnData for export, treating as no turns: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, MAP_TYPE);
            return result != null ? result : Map.of();
        } catch (JacksonException e) {
            log.warn("Failed to parse test case data for export, treating as empty: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * One projected export row: {@code turnIndex} is blank for a single-turn case, otherwise {@code "0"},
     * {@code "1"}, … in order; {@code data} is the effective merged view for that row (shared merged with
     * that turn's per-turn values, per-turn winning).
     */
    public record ProjectedRow(String turnIndex, Map<String, Object> data) {}
}
