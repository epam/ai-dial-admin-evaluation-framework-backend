package com.epam.aidial.evaluation.service.domain.exception;

import java.util.Map;

public class FilterValidationException extends ValidationException {

    private final Map<String, Object> details;

    public FilterValidationException(String message, Map<String, Object> details) {
        super(message);
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
