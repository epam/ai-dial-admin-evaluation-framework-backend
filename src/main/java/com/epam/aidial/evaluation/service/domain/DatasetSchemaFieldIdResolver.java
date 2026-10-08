package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves server-assigned ids of dataset schema fields and derives renames / removals against the current
 * schema. Pure function: no repository access, never mutates its input.
 */
@Component
@LogExecution
public class DatasetSchemaFieldIdResolver {

    public SchemaFieldResolution resolveForCreate(List<FieldDefinitionDto> incoming) {
        rejectDuplicateNames(incoming);
        final List<FieldDefinitionDto> schema = new ArrayList<>();
        for (final FieldDefinitionDto field : incoming) {
            if (field.getId() != null) {
                throw new ValidationException(
                        "Field '" + field.getName() + "' must not carry an id when creating a dataset");
            }
            schema.add(withId(field, newId()));
        }
        return new SchemaFieldResolution(schema, Map.of(), List.of());
    }

    public SchemaFieldResolution resolveForUpdate(List<FieldDefinitionDto> current, List<FieldDefinitionDto> incoming) {
        rejectDuplicateNames(incoming);
        rejectDuplicateIds(incoming);

        final FieldDefinitionDto[] claimedBy = new FieldDefinitionDto[current.size()];
        final String[] resolvedIds = new String[incoming.size()];
        bindExplicitIds(current, incoming, claimedBy, resolvedIds);
        bindByName(current, incoming, claimedBy, resolvedIds);

        final List<FieldDefinitionDto> schema = new ArrayList<>();
        for (int i = 0; i < incoming.size(); i++) {
            schema.add(withId(incoming.get(i), resolvedIds[i] != null ? resolvedIds[i] : newId()));
        }
        return new SchemaFieldResolution(
                schema, collectRenames(current, claimedBy), collectRemoved(current, claimedBy));
    }

    private void bindExplicitIds(
            List<FieldDefinitionDto> current,
            List<FieldDefinitionDto> incoming,
            FieldDefinitionDto[] claimedBy,
            String[] resolvedIds) {
        for (int i = 0; i < incoming.size(); i++) {
            final FieldDefinitionDto field = incoming.get(i);
            if (field.getId() == null) {
                continue;
            }
            final int idx = indexOfId(current, field.getId());
            if (idx < 0) {
                throw new ValidationException(
                        "Field '" + field.getName() + "' carries unknown id '" + field.getId() + "'");
            }
            claimedBy[idx] = field;
            resolvedIds[i] = field.getId();
        }
    }

    private void bindByName(
            List<FieldDefinitionDto> current,
            List<FieldDefinitionDto> incoming,
            FieldDefinitionDto[] claimedBy,
            String[] resolvedIds) {
        for (int i = 0; i < incoming.size(); i++) {
            final FieldDefinitionDto field = incoming.get(i);
            if (field.getId() != null) {
                continue;
            }
            for (int c = 0; c < current.size(); c++) {
                if (claimedBy[c] == null && current.get(c).getName().equals(field.getName())) {
                    claimedBy[c] = field;
                    final String storedId = current.get(c).getId();
                    resolvedIds[i] = storedId != null ? storedId : newId();
                    break;
                }
            }
        }
    }

    private Map<String, String> collectRenames(List<FieldDefinitionDto> current, FieldDefinitionDto[] claimedBy) {
        final Map<String, String> renames = new LinkedHashMap<>();
        for (int c = 0; c < current.size(); c++) {
            if (claimedBy[c] != null
                    && !claimedBy[c].getName().equals(current.get(c).getName())) {
                renames.put(current.get(c).getName(), claimedBy[c].getName());
            }
        }
        return renames;
    }

    private List<String> collectRemoved(List<FieldDefinitionDto> current, FieldDefinitionDto[] claimedBy) {
        final List<String> removed = new ArrayList<>();
        for (int c = 0; c < current.size(); c++) {
            if (claimedBy[c] == null) {
                removed.add(current.get(c).getName());
            }
        }
        return removed;
    }

    private int indexOfId(List<FieldDefinitionDto> current, String id) {
        for (int c = 0; c < current.size(); c++) {
            if (id.equals(current.get(c).getId())) {
                return c;
            }
        }
        return -1;
    }

    private void rejectDuplicateNames(List<FieldDefinitionDto> incoming) {
        final Set<String> seen = new HashSet<>();
        for (final FieldDefinitionDto field : incoming) {
            if (field.getName() != null && !seen.add(field.getName().toLowerCase(Locale.ROOT))) {
                throw new ValidationException("Duplicate field name (case-insensitive): '" + field.getName() + "'");
            }
        }
    }

    private void rejectDuplicateIds(List<FieldDefinitionDto> incoming) {
        final Set<String> seen = new HashSet<>();
        for (final FieldDefinitionDto field : incoming) {
            if (field.getId() != null && !seen.add(field.getId())) {
                throw new ValidationException("Duplicate field id: '" + field.getId() + "'");
            }
        }
    }

    private FieldDefinitionDto withId(FieldDefinitionDto field, String id) {
        return field.toBuilder().id(id).build();
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }
}
