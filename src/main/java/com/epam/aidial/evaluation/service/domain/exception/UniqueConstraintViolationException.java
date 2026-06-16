package com.epam.aidial.evaluation.service.domain.exception;

import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * Thrown when a unique constraint is violated (e.g. duplicate name for TestSuite or TestCase).
 */
@Getter
public class UniqueConstraintViolationException extends RuntimeException {

    private final List<String> duplicatedNames;

    public UniqueConstraintViolationException(String message, String duplicatedName) {
        super(message);
        this.duplicatedNames = duplicatedName != null ? List.of(duplicatedName) : Collections.emptyList();
    }

    public UniqueConstraintViolationException(String message, List<String> duplicatedNames) {
        super(message);
        this.duplicatedNames = duplicatedNames != null ? List.copyOf(duplicatedNames) : Collections.emptyList();
    }
}
