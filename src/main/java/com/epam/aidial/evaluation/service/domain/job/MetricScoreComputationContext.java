package com.epam.aidial.evaluation.service.domain.job;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;
import lombok.Getter;

/**
 * Immutable carrier for the metric-score computation phase (Phase 3). Carries the run, its suite, and
 * the metric-evaluation {@code computationId} to reuse, so the computed scores join the run's latest
 * computation.
 */
@Getter
@Builder
public class MetricScoreComputationContext {

    private final UUID testSuiteRunId;
    private final UUID testSuiteId;
    private final UUID computationId;
    private final AtomicBoolean cancellationSignal;
}
