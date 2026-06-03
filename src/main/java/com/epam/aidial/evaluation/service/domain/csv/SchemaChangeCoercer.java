package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Strict, schema-change-only value coercer used by {@code RevalidationService}.
 * Sibling of {@link SchemaTypeCoercer} (CSV-import, permissive). The two intentionally
 * differ: this one drops Integer/Long ↔ Boolean, *→ FILE except String identity,
 * and Object/Array → STRING — see openspec/specs/test-cases/spec.md.
 */
@Slf4j
@Component
@LogExecution
public class SchemaChangeCoercer {

    /**
     * Returns the coerced value, or the input value unchanged if no safe conversion exists.
     * {@code null} and unknown {@code targetType} are identity.
     */
    public Object coerce(Object value, SchemaFieldType targetType) {
        if (targetType == null || value == null) {
            return value;
        }
        return switch (targetType) {
            case STRING -> coerceToString(value);
            case INTEGER -> coerceToInteger(value);
            case NUMBER -> coerceToNumber(value);
            case BOOLEAN -> coerceToBoolean(value);
            case FILE -> coerceToFile(value);
            case OBJECT, ARRAY -> value;
        };
    }

    /**
     * Coerces every cell of {@code data} in-place against the supplied {@code schema}.
     * Returns a {@link CoercionResult} carrying the (possibly new) data map, the
     * total number of cells that actually changed value, and a flag indicating
     * whether the map differs from the input.
     */
    public CoercionResult coerceMap(Map<String, Object> data, List<FieldDefinitionDto> schema) {
        if (data == null || data.isEmpty() || schema == null || schema.isEmpty()) {
            return new CoercionResult(data, 0, false);
        }
        Map<String, SchemaFieldType> typeByName = new LinkedHashMap<>();
        for (FieldDefinitionDto field : schema) {
            if (field != null && field.getName() != null) {
                typeByName.put(field.getName(), field.getType());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(data);
        int coercedCells = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            SchemaFieldType targetType = typeByName.get(entry.getKey());
            if (targetType == null) {
                continue;
            }
            Object original = entry.getValue();
            Object coerced = coerce(original, targetType);
            if (coerced != original && !Objects.equals(coerced, original)) {
                result.put(entry.getKey(), coerced);
                coercedCells++;
            }
        }
        return new CoercionResult(result, coercedCells, coercedCells > 0);
    }

    private static Object coerceToString(Object value) {
        if (value instanceof String) {
            return value;
        }
        if (value instanceof Boolean boolVal) {
            return boolVal ? "true" : "false";
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Double) {
            return String.valueOf(value);
        }
        // Object (Map) and Array (List) and anything else — skip (do not stringify Java debug form)
        return value;
    }

    private static Object coerceToInteger(Object value) {
        if (value instanceof Long) {
            return value;
        }
        if (value instanceof Integer intVal) {
            return intVal.longValue();
        }
        if (value instanceof Double doubleVal) {
            if (doubleVal % 1 == 0) {
                return doubleVal.longValue();
            }
            return value; // fractional → skip (would lose data)
        }
        if (value instanceof String strVal) {
            try {
                return Long.parseLong(strVal);
            } catch (NumberFormatException e) {
                return value; // skip
            }
        }
        // Boolean → INTEGER intentionally NOT coerced (stricter than CSV import)
        return value;
    }

    private static Object coerceToNumber(Object value) {
        if (value instanceof Double) {
            return value;
        }
        if (value instanceof Long longVal) {
            return longVal.doubleValue();
        }
        if (value instanceof Integer intVal) {
            return intVal.doubleValue();
        }
        if (value instanceof String strVal) {
            try {
                return Double.parseDouble(strVal);
            } catch (NumberFormatException e) {
                return value; // skip
            }
        }
        // Boolean → NUMBER intentionally NOT coerced
        return value;
    }

    private static Object coerceToBoolean(Object value) {
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof String strVal) {
            if ("true".equals(strVal)) {
                return Boolean.TRUE;
            }
            if ("false".equals(strVal)) {
                return Boolean.FALSE;
            }
            return value; // any other string → skip
        }
        // Integer/Long/Double → BOOLEAN intentionally NOT coerced (stricter than CSV import)
        return value;
    }

    private static Object coerceToFile(Object value) {
        if (value instanceof String) {
            return value; // identity — FILE format check happens downstream in validator
        }
        // Non-String → FILE: skip (Boolean/Number/Object/Array can't be a file ref)
        return value;
    }

    /**
     * Result of a {@link #coerceMap(Map, List)} call.
     *
     * @param coercedData       the data map after coercion (may be the input reference if nothing changed)
     * @param coercedCellCount  number of (key, value) cells whose value changed
     * @param changed           true if at least one cell changed
     */
    public record CoercionResult(Map<String, Object> coercedData, int coercedCellCount, boolean changed) {}
}
