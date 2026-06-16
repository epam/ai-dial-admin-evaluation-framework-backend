package com.epam.aidial.evaluation.service.domain.exception;

public class UnsupportedSnapshotVersionException extends RuntimeException {
    public UnsupportedSnapshotVersionException(String message) {
        super(message);
    }
}
