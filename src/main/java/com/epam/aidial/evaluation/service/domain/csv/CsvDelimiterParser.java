package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the {@code delimiter} request parameter for CSV import/export endpoints.
 *
 * <p>Semantics preserved verbatim from the prior {@code TestCaseController.parseDelimiter}:
 * <ul>
 *   <li>{@code null} or empty → default {@code ','}.</li>
 *   <li>Single ASCII character (code point 0-127) → accepted as-is.</li>
 *   <li>Multi-character or non-ASCII input → {@link ValidationException}.</li>
 * </ul>
 */
@Component
@LogExecution
public class CsvDelimiterParser {

    private static final char DEFAULT_DELIMITER = ',';

    public char parse(String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            return DEFAULT_DELIMITER;
        }
        if (delimiter.length() != 1) {
            throw new ValidationException(
                    "CSV delimiter must be a single character, but length was: " + delimiter.length());
        }
        char c = delimiter.charAt(0);
        if (c > 127) {
            throw new ValidationException("CSV delimiter must be ASCII (code point 0-127); got non-ASCII character");
        }
        return c;
    }
}
