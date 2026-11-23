package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Converts CSV timestamps without changing the epoch-millisecond model. */
@Component
@LogExecution
public class CsvTimestampCodec {

    private static final DateTimeFormatter FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern ISO_UTC = Pattern.compile(
            "(?:[0-9]{4}|[+-][0-9]{4,9})-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-9]{2}:[0-5][0-9](?:\\.[0-9]{1,3})?Z");

    public String format(Long epochMillis) {
        return epochMillis == null ? "" : FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    public Long parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String value = raw.trim();
        try {
            if (INTEGER.matcher(value).matches()) {
                return Long.parseLong(value);
            }
            if (ISO_UTC.matcher(value).matches()) {
                return Instant.parse(value).toEpochMilli();
            }
        } catch (NumberFormatException | DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid or out-of-range CSV timestamp: '" + value + "'", e);
        }
        throw new IllegalArgumentException("Expected epoch milliseconds or an ISO UTC timestamp: '" + value + "'");
    }
}
