package com.epam.aidial.evaluation.query.service.metricscore;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 3 of a test suite run: computes metric-score statistics over the run's eval summaries via the
 * Query DSL and persists the results, reusing the run's metric-evaluation {@code computationId}.
 *
 * <p>Two kinds of computation:
 * <ul>
 *   <li><b>Per-metric statistics</b> — each built-in {@link BuiltInMetricStatistics#perMetric()}
 *       statistic (AVG/P10/P90/MIN/MAX) is executed once per numeric metric field, binding
 *       {@code :metricField}.
 *   <li><b>Run-level {@code overall}</b> — a per-suite definition taken from the run's suite snapshot
 *       ({@link MetricScoreComputationContext#getOverallScoreDefinition()}). When the suite has no
 *       definition (null), the {@linkplain BuiltInMetricStatistics#defaultOverall() default} query is
 *       used — computed <b>only when the run has exactly one numeric metric field</b> (so {@code overall}
 *       is that metric's average; the executor binds {@code :metricField} to the single field) and
 *       skipped otherwise, run directly over raw {@code eval_summaries}. A non-null {@code Mean}/
 *       {@code WeightedMean} is instead built directly against {@code test_case_metric_scores} by
 *       {@link OverallScoreDefinitionResolver} (one row per test case, so every test case counts equally
 *       regardless of row count). {@code CustomFunction} is an opaque, client-authored expression stored
 *       against {@code eval_summaries}/{@code metric::} fields — it is resolved and executed there
 *       unchanged, ungrouped, same weight-skew-by-row-count limitation as {@link BuiltInMetricStatistics}
 *       and {@code FilteredMetricScoreAggregator} (both already out of scope for this normalization). All
 *       three variants are computed regardless of metric count.
 * </ul>
 *
 * <p>Fault isolation: a {@link ValidationException} computing one score is logged and skipped so it
 * does not abort the rest. Any other runtime exception propagates to the caller unchanged.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricScoreComputationExecutor {

    private final BuiltInMetricStatistics builtInStatistics;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
    private final MetricScoreService metricScoreService;
    private final MetricFieldDiscoverer metricFieldDiscoverer;
    private final StructuredQueryService structuredQueryService;
    private final OverallScoreDefinitionResolver overallScoreDefinitionResolver;

    /** Computes and persists the run's metric scores for the computation described by {@code ctx}. */
    public void execute(MetricScoreComputationContext ctx) {
        final long computedAtMs = ctx.getComputedAtMs();
        final List<RunMetricSnapshot> snapshots = runMetricSnapshotRepository.findByRunIdAndComputationId(
                ctx.getTestSuiteRunId(), ctx.getComputationId());
        final List<MetricField> metricFields = metricFieldDiscoverer.discover(snapshots);
        if (metricFields.isEmpty()) {
            log.debug(
                    "No numeric metric fields for run {} computation {}; skipping metric score computation",
                    ctx.getTestSuiteRunId(),
                    ctx.getComputationId());
            return;
        }

        final List<MetricScoreResult> results = new ArrayList<>();

        // Per-metric statistics: each built-in statistic, once per metric field.
        for (final BuiltInMetricStatistics.MetricStatistic statistic : builtInStatistics.perMetric()) {
            results.addAll(computePerMetric(statistic.query(), statistic.name(), metricFields, ctx, computedAtMs));
        }

        // Run-level overall, from the suite's (snapshot) definition or the single-metric default.
        results.addAll(computeOverall(ctx, metricFields, computedAtMs));

        metricScoreService.saveAll(results);
        log.debug(
                "Computed {} metric score result(s) for run {} computation {}",
                results.size(),
                ctx.getTestSuiteRunId(),
                ctx.getComputationId());
    }

    /** Per-metric statistic: run the query once per field, binding {@code :metricField}. */
    private List<MetricScoreResult> computePerMetric(
            StructuredQuery query,
            String scoreName,
            List<MetricField> metricFields,
            MetricScoreComputationContext ctx,
            long computedAtMs) {
        final List<MetricScoreResult> results = new ArrayList<>();
        for (final MetricField metricField : metricFields) {
            final Map<String, Expr> params = baseParams(ctx);
            params.put(MetricScoreConstants.PARAM_METRIC_FIELD, new FieldExpr(metricField.flattenedName()));
            final Double value = executeScalar(query, params, scoreName, metricField.flattenedName(), ctx);
            if (value != null) {
                results.add(buildResult(ctx, scoreName, metricField.metricName(), value, computedAtMs));
            }
        }
        return results;
    }

    /**
     * Run-level {@code overall}. The default (no suite definition) is computed only when the run has
     * exactly one numeric metric field, directly over raw {@code eval_summaries} (unchanged). A non-null
     * {@code Mean}/{@code WeightedMean} is resolved via {@link OverallScoreDefinitionResolver} and executed
     * against {@code test_case_metric_scores} — one row per test case, so every test case counts equally
     * regardless of how many turns/requests/reruns produced its rows; both are built directly against that
     * entity by the resolver. A {@code CustomFunction} is resolved and executed directly against
     * {@code eval_summaries}, unchanged and ungrouped — an accepted, out-of-scope weight-skew limitation
     * (see class javadoc). All three remain runnable for any metric count.
     */
    private List<MetricScoreResult> computeOverall(
            MetricScoreComputationContext ctx, List<MetricField> metricFields, long computedAtMs) {
        final OverallScoreDefinition definition = ctx.getOverallScoreDefinition();
        final Double value = definition == null
                ? computeDefaultOverall(ctx, metricFields)
                : computeOverallScore(definition, metricFields, ctx);
        return value != null
                ? List.of(buildResult(
                        ctx,
                        MetricScoreConstants.SCORE_OVERALL,
                        MetricScoreConstants.SCORE_OVERALL,
                        value,
                        computedAtMs))
                : List.of();
    }

    private Double computeOverallScore(
            OverallScoreDefinition definition, List<MetricField> metricFields, MetricScoreComputationContext ctx) {
        final StructuredQuery resolved = overallScoreDefinitionResolver.resolve(definition, metricKeys(metricFields));
        if (resolved == null) {
            return null;
        }
        return executeScalar(
                resolved, baseParams(ctx), MetricScoreConstants.SCORE_OVERALL, MetricScoreConstants.SCORE_OVERALL, ctx);
    }

    private Double computeDefaultOverall(MetricScoreComputationContext ctx, List<MetricField> metricFields) {
        if (metricFields.size() != 1) {
            log.debug(
                    "Default overall skipped for run {}: {} metric fields (computed only for a single metric)",
                    ctx.getTestSuiteRunId(),
                    metricFields.size());
            return null;
        }
        final Map<String, Expr> params = baseParams(ctx);
        params.put(
                MetricScoreConstants.PARAM_METRIC_FIELD,
                new FieldExpr(metricFields.getFirst().flattenedName()));
        return executeScalar(
                builtInStatistics.defaultOverall(),
                params,
                MetricScoreConstants.SCORE_OVERALL,
                MetricScoreConstants.SCORE_OVERALL,
                ctx);
    }

    private Map<String, Expr> baseParams(MetricScoreComputationContext ctx) {
        final Map<String, Expr> params = new HashMap<>();
        params.put(
                MetricScoreConstants.PARAM_RUN_ID,
                new ValueExpr(ValueType.UUID, ctx.getTestSuiteRunId().toString()));
        params.put(
                MetricScoreConstants.PARAM_COMPUTATION_ID,
                new ValueExpr(ValueType.UUID, ctx.getComputationId().toString()));
        return params;
    }

    private Double executeScalar(
            StructuredQuery query,
            Map<String, Expr> params,
            String scoreName,
            String label,
            MetricScoreComputationContext ctx) {
        try {
            final QueryResultPage page = structuredQueryService.execute(query, params);
            final List<Map<String, Object>> rows = page.rows();
            if (rows.isEmpty()) {
                return null;
            }
            final Object value = rows.getFirst().get(MetricScoreConstants.VALUE_ALIAS);
            return value instanceof Number number ? number.doubleValue() : null;
        } catch (ValidationException e) {
            log.warn(
                    "Metric score '{}' over '{}' failed for run {}: {}",
                    scoreName,
                    label,
                    ctx.getTestSuiteRunId(),
                    e.getMessage(),
                    e);
            return null;
        }
    }

    private static List<String> metricKeys(List<MetricField> metricFields) {
        return metricFields.stream().map(MetricField::metricName).toList();
    }

    private MetricScoreResult buildResult(
            MetricScoreComputationContext ctx, String scoreName, String metricName, Double value, long computedAtMs) {
        return MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(ctx.getTestSuiteRunId())
                .testSuiteId(ctx.getTestSuiteId())
                .computationId(ctx.getComputationId())
                .metricScoreName(scoreName)
                .metricName(metricName)
                .value(value)
                .computedAtMs(computedAtMs)
                .build();
    }
}
