package com.epam.aidial.evaluation.service.domain.exception;

import java.util.Map;
import lombok.Getter;

@Getter
public class TooManyRunsException extends RuntimeException {

    private final Map<String, Object> details;

    public TooManyRunsException(String message, Map<String, Object> details) {
        super(message);
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }
}
