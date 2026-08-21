package com.epam.aidial.evaluation.query.service.translate;

import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Parses a {@link ValueExpr} — whose wire {@code value} is always a JSON string governed by an
 * explicit {@code value_type} (§4.2) — into the typed Java value that jOOQ binds as a parameter.
 * Parsing failures surface as {@link ValidationException} (HTTP 400) rather than 500s, since a bad
 * literal is a client error. {@code UUID} is normalised but returned as {@link String} because UUIDs
 * are stored as {@code VARCHAR(36)} in this project.
 */
@Component
@LogExecution
public class ValueExprToObjectMapper {

    /** Returns {@code null} for {@link ValueType#NULL}; comparison building turns that into IS NULL. */
    public Object map(ValueExpr expr) {
        if (expr == null || expr.valueType() == null) {
            throw new ValidationException("value expression must declare a value_type");
        }
        final ValueType type = expr.valueType();
        if (type == ValueType.NULL) {
            return null;
        }
        final String raw = expr.value();
        if (raw == null) {
            throw new ValidationException("value must not be null for value_type '" + type.code() + "'");
        }
        final String trimmed = raw.trim();
        return switch (type) {
            case STRING -> raw;
            case INTEGER -> parseInteger(trimmed);
            case LONG -> parseLong(trimmed);
            case DECIMAL -> parseDecimal(trimmed);
            case BOOLEAN -> parseBoolean(trimmed);
            case UUID -> parseUuid(trimmed);
            case DATE -> parseDate(trimmed);
            case TIMESTAMP -> parseLong(trimmed); // epoch milliseconds, per the project timestamp convention
            default -> null; // unreachable: handled above
        };
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new ValidationException("invalid integer literal: '" + value + "'");
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new ValidationException("invalid long/timestamp literal: '" + value + "'");
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ValidationException("invalid decimal literal: '" + value + "'");
        }
    }

    private static Boolean parseBoolean(String value) {
        final String normalized = value.toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        throw new ValidationException("invalid boolean literal: '" + value + "'");
    }

    private static String parseUuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invalid uuid literal: '" + value + "'");
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ValidationException("invalid date literal (expected ISO yyyy-MM-dd): '" + value + "'");
        }
    }
}
