package com.epam.aidial.evaluation.service.domain.analytics;

/**
 * Outcome of reconciling one batch of completed runs.
 *
 * @param written pass-count rows inserted
 * @param deferred runs left for a later pass because their latest computation is younger than the minimum age
 */
public record ReconciledBatch(int written, int deferred) {

    public static final ReconciledBatch EMPTY = new ReconciledBatch(0, 0);
}
