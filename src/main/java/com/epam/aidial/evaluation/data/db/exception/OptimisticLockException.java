package com.epam.aidial.evaluation.data.db.exception;

/**
 * Thrown by the data layer when an update fails due to optimistic locking (e.g. version mismatch).
 */
public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String message) {
        super(message);
    }
}
