package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Coerces a parsed cell value to match the declared schema field type.
 * Returns the value unchanged if coercion is not possible (coercion failure)
 * or if schema type is null (unknown schema).
 */
@Slf4j
@Component
@LogExecution
public class SchemaTypeCoercer {

    /**
     * Coerces a parsed cell value to match the declared schema type.
     *
     * @param value      the parsed cell value (from CsvCellParser or Jackson deserialization)
     * @param schemaType the declared schema field type (nullable — null means unknown)
     * @return coerced value, or original value unchanged if coercion fails or is unnecessary
     */
    public Object coerce(Object value, SchemaFieldType schemaType) {
        if (schemaType == null || value == null) {
            return value;
        }
        return switch (schemaType) {
            case STRING, FILE -> coerceToString(value);
            case INTEGER -> coerceToInteger(value);
            case NUMBER -> coerceToNumber(value);
            case BOOLEAN -> coerceToBoolean(value);
            case OBJECT, ARRAY -> value;
        };
    }

    private static Object coerceToString(Object value) {
        if (value instanceof String) {
            return value;
        }
        return String.valueOf(value);
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
            return value; // fractional — coercion failure
        }
        if (value instanceof Boolean boolVal) {
            return boolVal ? 1L : 0L;
        }
        if (value instanceof String strVal) {
            try {
                return Long.parseLong(strVal);
            } catch (NumberFormatException e) {
                return value; // coercion failure
            }
        }
        return value;
    }

    private static Object coerceToNumber(Object value) {
        if (value instanceof Double) {
            return value;
        }
        if (value instanceof Number numVal) {
            return numVal.doubleValue();
        }
        if (value instanceof Boolean boolVal) {
            return boolVal ? 1.0 : 0.0;
        }
        if (value instanceof String strVal) {
            try {
                return Double.parseDouble(strVal);
            } catch (NumberFormatException e) {
                return value; // coercion failure
            }
        }
        return value;
    }

    private static Object coerceToBoolean(Object value) {
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer intVal) {
            return intVal != 0;
        }
        if (value instanceof Long longVal) {
            return longVal != 0L;
        }
        if (value instanceof String strVal) {
            if ("true".equalsIgnoreCase(strVal)) {
                return true;
            }
            if ("false".equalsIgnoreCase(strVal)) {
                return false;
            }
            return value; // coercion failure
        }
        // Double → Boolean: coercion failure (ambiguous)
        return value;
    }
}
