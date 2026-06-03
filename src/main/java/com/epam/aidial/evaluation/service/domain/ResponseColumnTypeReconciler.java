package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.TypeMismatchException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reconciles JSONata evaluation results against the column's declared {@link SchemaFieldType}.
 * Applies safe coercions silently and throws {@link TypeMismatchException} for unsafe mismatches.
 */
@Slf4j
@Component
@LogExecution
public class ResponseColumnTypeReconciler {

    public Object reconcile(Object jsonataResult, SchemaFieldType declaredType) {
        if (declaredType == null) {
            return jsonataResult;
        }
        if (jsonataResult == null) {
            return null;
        }

        return switch (declaredType) {
            case ARRAY -> reconcileArray(jsonataResult);
            case STRING, FILE -> reconcileString(jsonataResult, declaredType);
            case INTEGER -> reconcileInteger(jsonataResult);
            case NUMBER -> reconcileNumber(jsonataResult);
            case BOOLEAN -> reconcileBoolean(jsonataResult);
            case OBJECT -> reconcileObject(jsonataResult);
        };
    }

    private Object reconcileArray(Object value) {
        if (value instanceof List<?>) {
            return value;
        }
        return Collections.singletonList(value);
    }

    private Object reconcileString(Object value, SchemaFieldType declaredType) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new TypeMismatchException(declaredType, actualTypeLabel(value));
    }

    private Object reconcileInteger(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof BigInteger bi) {
            return bi.longValueExact();
        }
        if (value instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return d.longValue();
            }
            throw new TypeMismatchException(
                    SchemaFieldType.INTEGER, "NUMBER", value, "fractional value not representable as integer");
        }
        if (value instanceof Float f) {
            float fv = f;
            if (fv == Math.floor(fv) && !Float.isInfinite(fv)) {
                return (long) fv;
            }
            throw new TypeMismatchException(
                    SchemaFieldType.INTEGER, "NUMBER", value, "fractional value not representable as integer");
        }
        if (value instanceof BigDecimal bd) {
            try {
                return bd.longValueExact();
            } catch (ArithmeticException ex) {
                throw new TypeMismatchException(
                        SchemaFieldType.INTEGER, "NUMBER", value, "fractional value not representable as integer");
            }
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                throw new TypeMismatchException(SchemaFieldType.INTEGER, "STRING", value, "not parseable as integer");
            }
        }
        throw new TypeMismatchException(SchemaFieldType.INTEGER, actualTypeLabel(value));
    }

    private Object reconcileNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ex) {
                throw new TypeMismatchException(SchemaFieldType.NUMBER, "STRING", value, "not parseable as number");
            }
        }
        throw new TypeMismatchException(SchemaFieldType.NUMBER, actualTypeLabel(value));
    }

    private Object reconcileBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
            throw new TypeMismatchException(SchemaFieldType.BOOLEAN, "STRING", value, "not parseable as boolean");
        }
        throw new TypeMismatchException(SchemaFieldType.BOOLEAN, actualTypeLabel(value));
    }

    private Object reconcileObject(Object value) {
        if (value instanceof Map<?, ?>) {
            return value;
        }
        throw new TypeMismatchException(SchemaFieldType.OBJECT, actualTypeLabel(value));
    }

    private String actualTypeLabel(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            return "STRING";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
            return "INTEGER";
        }
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof List<?>) {
            return "ARRAY";
        }
        if (value instanceof Map<?, ?>) {
            return "OBJECT";
        }
        return value.getClass().getSimpleName();
    }
}
