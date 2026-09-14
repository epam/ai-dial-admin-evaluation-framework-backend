package com.epam.aidial.evaluation.query.service.metricscore;

import com.epam.aidial.evaluation.configuration.properties.analytics.RunComparisonProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryMatchStats;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreValueDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonRunDto;
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
 */
@Slf4j
@Service
@LogExecution
public class RunComparisonService {

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

    /**
     * @param runIds exactly two distinct runs of the same suite, in the order they should be reported
     * @return one entry per run, in request order
     */
    public RunComparisonResponseDto compare(List<UUID> runIds) {
        requireTwoDistinctRuns(runIds);

        // Meta reads first, outside the analytics transaction.
        final TestSuiteRunResponseDto first = testSuiteRunService.getRun(runIds.get(0));
        final TestSuiteRunResponseDto second = testSuiteRunService.getRun(runIds.get(1));
        requireSameSuite(first, second);
        final OverallScoreDefinition firstOverallScoreDef = overallScoreDefinition(first);
        final OverallScoreDefinition secondOverallScoreDef = overallScoreDefinition(second);

        // ComputationResolver requires an ambient analytics transaction (its own contract). Resolving
        // both computations up front keeps that contract satisfied without extending it to the meta
        // read below, which does not need it.
        final ComputationIds computationIds = analyticsTransactionTemplate.execute(
                status -> new ComputationIds(requireComputation(first.getId()), requireComputation(second.getId())));

        // Meta read, ahead of the analytics transaction below: a metaDsl query issued inside an
        // analytics transaction would not join it and would silently run in autocommit on a
        // different connection (design D9).
        final List<RunMetricSnapshot> firstSnapshots =
                runMetricSnapshotRepository.findByRunIdAndComputationId(first.getId(), computationIds.first());
        final List<RunMetricSnapshot> secondSnapshots =
                runMetricSnapshotRepository.findByRunIdAndComputationId(second.getId(), computationIds.second());

        // This transaction gives the aggregate queries below a consistent snapshot with each other,
        // but not with computation resolution above: that ran in its own, earlier transaction (see
        // the comment on computationIds), so a computation could resolve to "latest" there and a
        // newer one land before this transaction starts. Computations are append-only, so the worst
        // case is aggregating against a slightly stale "latest", never a torn or inconsistent read.
        // @Transactional on a self-invoked helper would open none at all, so the template is explicit.
        return analyticsTransactionTemplate.execute(status -> {
            final AggregationInputs firstInputs =
                    resolveInputs(first.getId(), computationIds.first(), second.getId(), computationIds.second());
            final AggregationInputs secondInputs =
                    resolveInputs(second.getId(), computationIds.second(), first.getId(), computationIds.first());

            return RunComparisonResponseDto.builder()
                    .runs(List.of(
                            aggregateScores(firstInputs, firstOverallScoreDef, firstSnapshots),
                            aggregateScores(secondInputs, secondOverallScoreDef, secondSnapshots)))
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

    private RunComparisonRunDto aggregateScores(
            AggregationInputs inputs, OverallScoreDefinition overallScoreDef, List<RunMetricSnapshot> snapshots) {
        final EvalSummaryMatchStats stats = inputs.stats();
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

    /** Both runs' resolved computation ids, kept together since every downstream read needs both. */
    private record ComputationIds(UUID first, UUID second) {}
}
