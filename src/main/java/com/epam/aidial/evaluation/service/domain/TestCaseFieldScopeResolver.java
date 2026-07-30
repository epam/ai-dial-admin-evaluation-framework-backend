package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for a dataset field's <b>scope</b> (shared vs per-turn), derived from the
 * {@code perTurn} flag on each {@link FieldDefinitionDto}. Shared fields live in a case's {@code data}
 * map (test-case-level, constant across turns); per-turn fields live in each {@code multiTurnData[i]}
 * map. This resolver is reused by write-time validation, the CSV import/export split, and (via the
 * per-field flag) the run-selection filter bindings, so the shared/per-turn partitioning is defined
 * exactly once.
 */
@Component
@LogExecution
public class TestCaseFieldScopeResolver {

    /** Names of the schema's per-turn (`perTurn=true`) fields. */
    public Set<String> perTurnFieldNames(List<FieldDefinitionDto> schema) {
        return safe(schema).stream()
                .filter(TestCaseFieldScopeResolver::isPerTurn)
                .map(FieldDefinitionDto::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Names of the schema's shared (`perTurn` false/absent) fields. */
    public Set<String> sharedFieldNames(List<FieldDefinitionDto> schema) {
        return safe(schema).stream()
                .filter(field -> !isPerTurn(field))
                .map(FieldDefinitionDto::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Splits the schema into its shared and per-turn sub-schemas in a single pass, so callers that need
     * both partitions do not iterate the field list twice.
     */
    public SchemaSplit splitSchema(List<FieldDefinitionDto> schema) {
        final List<FieldDefinitionDto> sharedFields = new ArrayList<>();
        final List<FieldDefinitionDto> perTurnFields = new ArrayList<>();
        for (final FieldDefinitionDto field : safe(schema)) {
            if (isPerTurn(field)) {
                perTurnFields.add(field);
            } else {
                sharedFields.add(field);
            }
        }
        return new SchemaSplit(List.copyOf(sharedFields), List.copyOf(perTurnFields));
    }

    /** A field is per-turn only when {@code perTurn} is explicitly {@code true}; null/false is shared. */
    private static boolean isPerTurn(FieldDefinitionDto field) {
        return Boolean.TRUE.equals(field.getPerTurn());
    }

    /**
     * Splits a flat data map (e.g. one CSV row's columns) into its shared and per-turn partitions by
     * schema scope. A key that is not a known schema field is kept in the shared partition (it will be
     * surfaced as an unknown-field warning by validation, not silently routed to a turn).
     */
    public Partition partition(Map<String, Object> flat, List<FieldDefinitionDto> schema) {
        final Set<String> perTurn = perTurnFieldNames(schema);
        final Map<String, Object> sharedPart = new LinkedHashMap<>();
        final Map<String, Object> perTurnPart = new LinkedHashMap<>();
        if (flat != null) {
            for (final Map.Entry<String, Object> entry : flat.entrySet()) {
                if (perTurn.contains(entry.getKey())) {
                    perTurnPart.put(entry.getKey(), entry.getValue());
                } else {
                    sharedPart.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new Partition(sharedPart, perTurnPart);
    }

    private static List<FieldDefinitionDto> safe(List<FieldDefinitionDto> schema) {
        return schema != null ? schema.stream().filter(f -> f != null).toList() : List.of();
    }

    /** A flat data map split by scope: {@code shared} keys and {@code perTurn} keys. */
    public record Partition(Map<String, Object> shared, Map<String, Object> perTurn) {}

    /** A schema split by scope: {@code shared} sub-schema and {@code perTurn} sub-schema. */
    public record SchemaSplit(List<FieldDefinitionDto> shared, List<FieldDefinitionDto> perTurn) {}
}
