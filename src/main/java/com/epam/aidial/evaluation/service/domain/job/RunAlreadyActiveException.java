package com.epam.aidial.evaluation.service.domain.job;

import java.util.UUID;

/**
 * Thrown when an attempt is made to dispatch a run that is already active (a handle is already
 * registered in {@link ActiveRunRegistry} for the given runId). Maps to HTTP 409 (Conflict) in the
 * web layer.
 */
public class RunAlreadyActiveException extends RuntimeException {

    private final UUID runId;

    public RunAlreadyActiveException(UUID runId) {
        super("Run " + runId + " is already active");
        this.runId = runId;
    }

    public UUID getRunId() {
        return runId;
    }
}
