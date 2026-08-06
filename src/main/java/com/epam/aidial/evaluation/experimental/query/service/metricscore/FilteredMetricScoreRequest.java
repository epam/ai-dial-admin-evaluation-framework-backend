package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import java.util.List;
import java.util.UUID;

/**
 * One run's inputs for a filtered metric-score recomputation.
 *
 * @param runId the run whose eval summaries are aggregated
 * @param computationId the run's resolved computation; pinned because re-evaluations mint new ones and all
 *     coexist
 * @param unmatchedEvalSummaryIds rows to <strong>exclude</strong>; empty means aggregate the whole run, in
 *     which case no predicate is grafted at all
 * @param metricFields the run's fully discovered numeric metric fields — never a subset, since a mean's
 *     divisor is the size of this list
 * @param overallScoreDefinition the suite snapshot's definition, or {@code null} for the default overall
 *     (computed only for a single-metric run)
 */
public record FilteredMetricScoreRequest(
        UUID runId,
        UUID computationId,
        List<UUID> unmatchedEvalSummaryIds,
        List<MetricField> metricFields,
        OverallScoreDefinition overallScoreDefinition) {}
