package com.epam.aidial.evaluation.service.domain.dto;

/**
 * Status of an async re-validation task.
 */
public enum RevalidationStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT
}
