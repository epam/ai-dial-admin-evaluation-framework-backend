package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;
import lombok.Getter;

/**
 * Immutable carrier for the metric-score computation phase (Phase 3). Carries the run, its suite, and
 * the metric-evaluation {@code computationId} to reuse, so the computed scores join the run's latest
 * computation.
 *
 * <p>{@code overallScoreDefinition} is the run's {@code overall} definition, taken from the suite
 * snapshot: {@code null} means "use the system default" (computed only for single-metric runs); a
 * non-null value is the suite's typed {@code Mean}/{@code WeightedMean}/{@code CustomFunction} definition
 * (computed regardless of metric count).
 *
 * <p>{@code computedAtMs} is the single timestamp shared by every result of this computation, resolved
 * by the caller (from {@link java.time.Clock}) rather than by the executor itself.
 */
@Getter
@Builder
public class MetricScoreComputationContext {

    private final UUID testSuiteRunId;
    private final UUID testSuiteId;
    private final UUID computationId;
    private final OverallScoreDefinition overallScoreDefinition;
    private final long computedAtMs;
    private final AtomicBoolean cancellationSignal;
}
