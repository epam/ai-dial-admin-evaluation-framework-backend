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
@Builder
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
}
