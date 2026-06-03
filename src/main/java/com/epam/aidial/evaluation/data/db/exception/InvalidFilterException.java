package com.epam.aidial.evaluation.data.db.exception;

import java.util.Map;
import lombok.Getter;

/**
 * Thrown by the data layer when a filter condition is invalid (unknown field, invalid operator, or value parse error).
 */
@Getter
public class InvalidFilterException extends RuntimeException {

    private final Map<String, Object> details;

    public InvalidFilterException(String message, Map<String, Object> details) {
        super(message);
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }
}
