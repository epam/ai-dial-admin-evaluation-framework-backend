package com.epam.aidial.evaluation.service.domain.exception;

import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Detects PostgreSQL unique constraint violations (SQLSTATE 23505) in exception cause chains.
 */
public final class UniqueConstraintViolationDetector {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private UniqueConstraintViolationDetector() {}

    /**
     * Returns true if the given throwable or any of its causes is a unique constraint violation (23505).
     */
    public static boolean isUniqueViolation(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof SQLException) {
                if (UNIQUE_VIOLATION_SQL_STATE.equals(((SQLException) current).getSQLState())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * If the given exception is a unique constraint violation, throws {@link UniqueConstraintViolationException}
     * with the given message. Callers should rethrow the original exception otherwise.
     */
    public static void rethrowIfUniqueViolation(DataIntegrityViolationException ex, String message) {
        if (isUniqueViolation(ex)) {
            throw new UniqueConstraintViolationException(message, (String) null);
        }
    }

    /**
     * If the given exception is a unique constraint violation, throws {@link UniqueConstraintViolationException}
     * with the given message and duplicated name. Callers should rethrow the original exception otherwise.
     */
    public static void rethrowIfUniqueViolation(
            DataIntegrityViolationException ex, String message, String duplicatedName) {
        if (isUniqueViolation(ex)) {
            throw new UniqueConstraintViolationException(message, duplicatedName);
        }
    }
}
