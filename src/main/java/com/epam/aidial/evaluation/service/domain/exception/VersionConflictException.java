package com.epam.aidial.evaluation.service.domain.exception;

import lombok.Getter;

/**
 * Thrown when optimistic locking fails (e.g. If-Match version does not match current entity version).
 */
@Getter
public class VersionConflictException extends RuntimeException {

    private final Long expectedVersion;
    private final Object resourceId;

    public VersionConflictException(String message, Object resourceId, Long expectedVersion) {
        super(message);
        this.resourceId = resourceId;
        this.expectedVersion = expectedVersion;
    }
}
