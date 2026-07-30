package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.analytics.RunComparisonProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryMatchStats;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import com.epam.aidial.evaluation.service.domain.analytics.RunComparisonProvider;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreValueDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonRunDto;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compares two runs of one suite over their shared eval-summary rows, recomputing each run's metric scores
 * across only those rows and returning the ids of the rows that did not match.
 *
 * <p>Lives in the experimental package because the recomputation goes through the structured-query service,
 * and is reached from the stable layers through {@link RunComparisonProvider}.
 */
@Slf4j
@Service
@LogExecution
public class RunComparisonService implements RunComparisonProvider {

    private static final int REQUIRED_RUN_COUNT = 2;

    private final TestSuiteRunService testSuiteRunService;
    private final ComputationResolver computationResolver;
    private final EvalSummaryRepository evalSummaryRepository;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
    private final MetricFieldDiscoverer metricFieldDiscoverer;
    private final FilteredMetricScoreAggregator scoreAggregator;
    private final RunComparisonProperties properties;
    private final TransactionTemplate analyticsTransactionTemplate;

    public RunComparisonService(
            TestSuiteRunService testSuiteRunService,
            ComputationResolver computationResolver,
            EvalSummaryRepository evalSummaryRepository,
            RunMetricSnapshotRepository runMetricSnapshotRepository,
            MetricFieldDiscoverer metricFieldDiscoverer,
            FilteredMetricScoreAggregator scoreAggregator,
            RunComparisonProperties properties,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.testSuiteRunService = testSuiteRunService;
        this.computationResolver = computationResolver;
        this.evalSummaryRepository = evalSummaryRepository;
        this.runMetricSnapshotRepository = runMetricSnapshotRepository;
        this.metricFieldDiscoverer = metricFieldDiscoverer;
        this.scoreAggregator = scoreAggregator;
        this.properties = properties;
        this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager);
        this.analyticsTransactionTemplate.setReadOnly(true);
    }

    @Override
    public RunComparisonResponseDto compare(List<UUID> runIds) {
        requireTwoDistinctRuns(runIds);

        // Meta reads first, outside the analytics transaction.
        final TestSuiteRunResponseDto first = testSuiteRunService.getRun(runIds.get(0));
        final TestSuiteRunResponseDto second = testSuiteRunService.getRun(runIds.get(1));
        requireSameSuite(first, second);
        final OverallScoreDefinition firstOverallScoreDef = overallScoreDefinition(first);
        final OverallScoreDefinition secondOverallScoreDef = overallScoreDefinition(second);

        // ComputationResolver requires an ambient analytics transaction (its own contract), and one
        // transaction also gives every aggregate query a consistent snapshot. @Transactional on a
        // self-invoked helper would open none at all, so the template is explicit.
        return analyticsTransactionTemplate.execute(status -> {
            final UUID firstComputation = requireComputation(first.getId());
            final UUID secondComputation = requireComputation(second.getId());

            final AggregationInputs firstInputs =
                    resolveInputs(first.getId(), firstComputation, second.getId(), secondComputation);
            final AggregationInputs secondInputs =
                    resolveInputs(second.getId(), secondComputation, first.getId(), firstComputation);

            return RunComparisonResponseDto.builder()
                    .runs(List.of(
                            aggregateScores(firstInputs, firstOverallScoreDef),
                            aggregateScores(secondInputs, secondOverallScoreDef)))
                    .build();
        });
    }

    /** Counts, then the cap, then — only if it passes — the ids. */
    private AggregationInputs resolveInputs(UUID runId, UUID computationId, UUID otherRunId, UUID otherComputationId) {
        final EvalSummaryMatchStats stats =
                evalSummaryRepository.countMatches(runId, computationId, otherRunId, otherComputationId);
        requireUnmatchedWithinCap(runId, stats);
        final List<UUID> unmatchedIds =
                evalSummaryRepository.findUnmatchedIds(runId, computationId, otherRunId, otherComputationId);
        return new AggregationInputs(runId, computationId, stats, unmatchedIds);
    }

    private RunComparisonRunDto aggregateScores(AggregationInputs inputs, OverallScoreDefinition overallScoreDef) {
        final EvalSummaryMatchStats stats = inputs.stats();
        final List<RunMetricSnapshot> snapshots =
                runMetricSnapshotRepository.findByRunIdAndComputationId(inputs.runId(), inputs.computationId());
        final List<MetricScoreValueDto> scores = stats.matchedRows() == 0
                // Nothing matched: every aggregate would be NULL and therefore omitted, so skip the queries
                // rather than binding the entire run for a guaranteed-empty result.
                ? List.of()
                : scoreAggregator.aggregate(new FilteredMetricScoreRequest(
                        inputs.runId(),
                        inputs.computationId(),
                        inputs.unmatchedIds(),
                        metricFieldDiscoverer.discover(snapshots),
                        overallScoreDef));

        return RunComparisonRunDto.builder()
                .runId(inputs.runId())
                .computationId(inputs.computationId())
                .totalRowCount(stats.totalRows())
                .matchedRowCount(stats.matchedRows())
                .matchedSuccessRowCount(stats.matchedSuccessRows())
                .avgExecDurationMs(toDouble(stats.avgExecDurationMs()))
                .unmatchedEvalSummaryIds(inputs.unmatchedIds())
                .scores(scores)
                .build();
    }

    private void requireTwoDistinctRuns(List<UUID> runIds) {
        if (runIds == null || runIds.size() != REQUIRED_RUN_COUNT) {
            throw new ValidationException("runIds must contain exactly " + REQUIRED_RUN_COUNT + " run ids, got "
                    + (runIds == null ? 0 : runIds.size()));
        }
        if (new HashSet<>(runIds).size() != REQUIRED_RUN_COUNT) {
            throw new ValidationException("runIds must reference two distinct runs");
        }
    }

    private void requireSameSuite(TestSuiteRunResponseDto first, TestSuiteRunResponseDto second) {
        if (!first.getTestSuiteId().equals(second.getTestSuiteId())) {
            throw new InvalidOperationException("Runs belong to different test suites and cannot be compared");
        }
    }

    /**
     * The run's snapshot {@code overallScore}, or null when the suite defined none.
     *
     * <p>A missing snapshot is rejected, matching the export path's treatment of legacy runs. The snapshot's
     * schema <em>version</em> is deliberately not gated: only {@code overallScore} is read here, and a legacy
     * snapshot lacking the field deserializes to null, which the default-overall rule already handles.
     */
    private OverallScoreDefinition overallScoreDefinition(TestSuiteRunResponseDto run) {
        final SuiteSnapshotDto snapshot = run.getSuiteSnapshot();
        if (snapshot == null) {
            throw new SnapshotSuiteMissingException(
                    "Run " + run.getId() + " has no suite_snapshot; legacy runs cannot be compared");
        }
        return snapshot.getOverallScore();
    }

    private UUID requireComputation(UUID runId) {
        return computationResolver
                .resolve(null, runId)
                .orElseThrow(() -> new InvalidOperationException(
                        "Run " + runId + " has no metric computation; nothing to compare"));
    }

    private void requireUnmatchedWithinCap(UUID runId, EvalSummaryMatchStats stats) {
        final long unmatched = stats.totalRows() - stats.matchedRows();
        if (unmatched > properties.getMaxUnmatchedRows()) {
            throw new InvalidOperationException("Run " + runId + " has " + unmatched
                    + " non-matching rows, which exceeds the limit of " + properties.getMaxUnmatchedRows()
                    + " (analytics.comparison.max-unmatched-rows)");
        }
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** One side's resolved inputs, so the two directions are aggregated symmetrically. */
    private record AggregationInputs(
            UUID runId, UUID computationId, EvalSummaryMatchStats stats, List<UUID> unmatchedIds) {}
}
