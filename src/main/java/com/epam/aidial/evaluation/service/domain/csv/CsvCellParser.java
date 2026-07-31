package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Infers primitive type from CSV cell string (INTEGER, NUMBER, BOOLEAN, STRING).
 * Cells that look like JSON object/array remain STRING (no OBJECT/ARRAY auto-inference).
 */
@Component
@LogExecution
public class CsvCellParser {

    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+\\.?\\d*$");

    /**
     * Parses a cell value to a typed Object.
     *
     * @param cell raw cell string (may be null or blank)
     * @return Long, Double, Boolean, or String; never null (blank returns empty string)
     */
    public Object parseCell(String cell) {
        if (cell == null || cell.isBlank()) {
            return "";
        }
        String trimmed = cell.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isBoolean(trimmed)) {
            return parseBoolean(trimmed);
        }
        if (INTEGER_PATTERN.matcher(trimmed).matches()) {
            return Long.parseLong(trimmed);
        }
        if (NUMBER_PATTERN.matcher(trimmed).matches()) {
            return Double.parseDouble(trimmed);
        }
        return cell;
    }

    /**
     * Returns inferred type name for a cell value (for CsvColumnInfoDto).
     */
    public String inferTypeName(String cell) {
        if (cell == null || cell.isBlank()) {
            return "STRING";
        }
        String trimmed = cell.trim();
        if (isBoolean(trimmed)) {
            return "BOOLEAN";
        }
        if (INTEGER_PATTERN.matcher(trimmed).matches()) {
            return "INTEGER";
        }
        if (NUMBER_PATTERN.matcher(trimmed).matches()) {
            return "NUMBER";
        }
        return "STRING";
    }

    private static boolean isBoolean(String value) {
        String lower = value.toLowerCase();
        return "true".equals(lower) || "false".equals(lower);
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value);
    }
}
