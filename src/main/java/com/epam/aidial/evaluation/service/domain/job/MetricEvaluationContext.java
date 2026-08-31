package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;
import lombok.Getter;

/**
 * Immutable carrier for metric evaluation phase state.
 * Contains only serializable/transport-safe fields — execution-strategy-specific
 * concerns (e.g. semaphores) are built by the executor implementation.
 */
@Getter
@Builder(toBuilder = true)
public class MetricEvaluationContext {

    private final UUID computationId;
    private final Long computedAtMs;
    private final UUID testSuiteRunId;
    private final UUID testSuiteId;
    private final Long runCreatedAtMs;
    private final List<AggregatedMetricDefinition> aggregatedTsmds;
    private final AtomicBoolean cancellationSignal;
    private final MetricEvaluationProperties.Retry retryConfig;
    private final int defaultConcurrencyPerProvider;
    private final int batchSize;
    private final long perResultTimeoutMs;

    /**
     * Whether this run is inline (see {@code InlineModeDetector} and the {@code
     * inline-metric-evaluation} change's {@code design.md} Decision 1). {@code false} by default
     * (the correct value for every run predating this change, and for a run dispatched with {@code
     * skipDeploymentPhase = true}, which never invokes the detector). When {@code true}, {@code
     * InProcessMetricEvaluationExecutor}'s row loop skips SUCCESS rows (already scored inline during
     * Phase 1) and only propagates non-SUCCESS rows.
     */
    private final boolean inlineMode;

    /**
     * The chain's request labels in index order: element 0 is request #0's {@code requestName},
     * element {@code i > 0} is {@code additionalRequests[i - 1].name}. Lets Phase 2 resolve a
     * result row's {@code request.name} by {@code requestIndex} without a new analytics column. See
     * {@link #requestLabelAt(int)}.
     */
    private final List<String> requestLabels;

    /**
     * Resolves the request label at the given chain position. Returns {@code null} when the list is
     * absent/empty, the index is out of range, or the request at that position is unlabelled — the
     * same "no label" outcome in every case.
     */
    public String requestLabelAt(int requestIndex) {
        if (requestLabels == null || requestIndex < 0 || requestIndex >= requestLabels.size()) {
            return null;
        }
        return requestLabels.get(requestIndex);
    }
}
