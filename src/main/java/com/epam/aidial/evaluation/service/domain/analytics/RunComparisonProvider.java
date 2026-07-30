package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonResponseDto;
import java.util.List;
import java.util.UUID;

/**
 * Compares two runs of one test suite over the eval-summary rows they have in common.
 *
 * <p>Declared here, in the stable layer, but implemented in {@code experimental.query.service.metricscore}
 * because the recomputation goes through the structured-query service. The inversion is what lets the
 * controller — which must live in the stable web layer to raise the shared exception types — reach that
 * implementation without the web layer depending on experimental code. Same shape as
 * {@code MetricScoreComputation} and {@code RunnableTestCaseSelector}.
 */
public interface RunComparisonProvider {

    /**
     * @param runIds exactly two distinct runs of the same suite, in the order they should be reported
     * @return one entry per run, in request order
     */
    RunComparisonResponseDto compare(List<UUID> runIds);
}
