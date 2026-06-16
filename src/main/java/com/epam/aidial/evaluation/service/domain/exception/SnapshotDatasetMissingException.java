package com.epam.aidial.evaluation.service.domain.exception;

/**
 * Thrown when a snapshot phase cannot resolve the dataset that the test suite references —
 * either at snapshot capture time (run init) or when synthesising a transient snapshot for a
 * legacy run whose {@code suite_snapshot} column is null. Maps to error code
 * {@code SNAPSHOT_DATASET_MISSING}.
 */
public class SnapshotDatasetMissingException extends RuntimeException {
    public SnapshotDatasetMissingException(String message) {
        super(message);
    }
}
