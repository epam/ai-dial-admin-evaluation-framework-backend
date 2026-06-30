package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.COLUMN_SEPARATOR;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinition;
import com.epam.aidial.evaluation.data.db.repository.MetricScoreDefinitionRepository;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputation;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 3 of a test suite run: computes metric-score statistics over the run's eval summaries via the
 * Query DSL and persists the results, reusing the run's metric-evaluation {@code computationId}.
 *
 * <p>Two kinds of computation:
 * <ul>
 *   <li><b>Per-metric statistics</b> — each seeded {@link MetricScoreDefinition} (AVG/P10/P90/MIN/MAX)
 *       is executed once per numeric metric field, binding {@code :metricField}.
 *   <li><b>Run-level {@code overall}</b> — a per-suite definition taken from the run's suite snapshot
 *       ({@link MetricScoreComputationContext#getOverallExpression()}). When the suite has no custom
 *       definition (null), the {@linkplain MetricScoreConstants#DEFAULT_OVERALL_EXPRESSION default}
 *       is used, but computed <b>only when the run has exactly one numeric metric field</b> (so the
 *       mean is unambiguous); with more than one field no {@code overall} row is produced. A custom
 *       expression is computed regardless of metric count.
 * </ul>
 *
 * <p>Fault isolation: a {@link ValidationException} computing one score is logged and skipped so it
 * does not abort the rest. Any other runtime exception propagates to the caller unchanged.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricScoreComputationExecutor implements MetricScoreComputation {

    /** DSL aggregate-function name used to build the per-metric averages bound to {@code :metricAvgs}. */
    private static final String AVG_FUNCTION = "avg";

    private final MetricScoreDefinitionRepository definitionRepository;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
    private final MetricScoreService metricScoreService;
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;
    private final StructuredQueryService structuredQueryService;
    private final ObjectMapper objectMapper;

    @Override
    public void execute(MetricScoreComputationContext ctx) {
        if (isCancelled(ctx)) {
            return;
        }
        final List<RunMetricSnapshot> snapshots = runMetricSnapshotRepository.findByRunIdAndComputationId(
                ctx.getTestSuiteRunId(), ctx.getComputationId());
        final List<MetricField> metricFields = discoverMetricFields(snapshots);
        if (metricFields.isEmpty()) {
            log.debug(
                    "No numeric metric fields for run {} computation {}; skipping metric score computation",
                    ctx.getTestSuiteRunId(),
                    ctx.getComputationId());
            return;
        }

        final List<MetricScoreResult> results = new ArrayList<>();

        // Per-metric statistics: each seeded definition, once per metric field.
        for (final MetricScoreDefinition definition : definitionRepository.findAll()) {
            if (isCancelled(ctx)) {
                return;
            }
            final StructuredQuery query = parseExpression(definition.getExpression(), definition.getName());
            if (query != null) {
                computePerMetric(query, definition, metricFields, ctx, results);
            }
        }

        // Run-level overall, from the suite's (snapshot) definition or the single-metric default.
        if (!isCancelled(ctx)) {
            computeOverall(ctx, metricFields, results);
        }

        metricScoreService.saveAll(results);
        log.debug(
                "Computed {} metric score result(s) for run {} computation {}",
                results.size(),
                ctx.getTestSuiteRunId(),
                ctx.getComputationId());
    }

    /** Per-metric statistic: run the expression once per field, binding {@code :metricField}. */
    private void computePerMetric(
            StructuredQuery query,
            MetricScoreDefinition definition,
            List<MetricField> metricFields,
            MetricScoreComputationContext ctx,
            List<MetricScoreResult> results) {
        for (final MetricField metricField : metricFields) {
            final Map<String, Expr> params = baseParams(ctx);
            params.put(MetricScoreConstants.PARAM_METRIC_FIELD, new FieldExpr(metricField.flattenedName()));
            final Double value = executeScalar(query, params, definition.getName(), metricField.flattenedName(), ctx);
            if (value != null) {
                results.add(buildResult(ctx, definition.getName(), metricField.metricName(), value));
            }
        }
    }

    /**
     * Run-level {@code overall}: a custom per-suite expression (computed for any metric count), or the
     * system default — computed only when the run has exactly one numeric metric field (so the mean is
     * unambiguous), otherwise skipped (no {@code overall} row).
     */
    private void computeOverall(
            MetricScoreComputationContext ctx, List<MetricField> metricFields, List<MetricScoreResult> results) {
        final boolean isDefault = ctx.getOverallExpression() == null;
        if (isDefault && metricFields.size() != 1) {
            log.debug(
                    "Default overall skipped for run {}: {} metric fields (computed only for a single metric)",
                    ctx.getTestSuiteRunId(),
                    metricFields.size());
            return;
        }
        final String expression =
                isDefault ? MetricScoreConstants.DEFAULT_OVERALL_EXPRESSION : ctx.getOverallExpression();
        final StructuredQuery query = parseExpression(expression, MetricScoreConstants.SCORE_OVERALL);
        if (query == null) {
            return;
        }
        final Map<String, Expr> params = baseParams(ctx);
        params.put(MetricScoreConstants.PARAM_METRIC_AVGS, metricAvgsArray(metricFields));
        final Double value = executeScalar(
                query, params, MetricScoreConstants.SCORE_OVERALL, MetricScoreConstants.SCORE_OVERALL, ctx);
        if (value != null) {
            results.add(
                    buildResult(ctx, MetricScoreConstants.SCORE_OVERALL, MetricScoreConstants.SCORE_OVERALL, value));
        }
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

    /** The run's per-metric {@code avg(metric:<tsmd>:<field>)} terms, as an array for {@code mean(...)}. */
    private ArrayExpr metricAvgsArray(List<MetricField> metricFields) {
        final List<Expr> avgs = new ArrayList<>(metricFields.size());
        for (final MetricField metricField : metricFields) {
            avgs.add(new FnExpr(AVG_FUNCTION, false, List.of(new FieldExpr(metricField.flattenedName()))));
        }
        return new ArrayExpr(avgs);
    }

    private Double executeScalar(
            StructuredQuery query,
            Map<String, Expr> params,
            String scoreName,
            String label,
            MetricScoreComputationContext ctx) {
        try {
            final QueryResultPage page = structuredQueryService.execute(query, params);
            if (page.rows().isEmpty()) {
                return null;
            }
            final Object value = page.rows().getFirst().get(MetricScoreConstants.VALUE_ALIAS);
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

    /** Flattens the run's metric snapshots into the numeric {@code metric:<tsmd>:<field>} columns. */
    private List<MetricField> discoverMetricFields(List<RunMetricSnapshot> snapshots) {
        final List<MetricField> fields = new ArrayList<>();
        for (final RunMetricSnapshot snapshot : snapshots) {
            for (final String outputField : outputSchemaFieldExtractor.extractFieldNames(snapshot.getOutputSchema())) {
                final String flattenedName =
                        METRIC_COLUMN_PREFIX + snapshot.getTsmdName() + COLUMN_SEPARATOR + outputField;
                final String metricName = snapshot.getTsmdName() + "." + outputField;
                fields.add(new MetricField(flattenedName, metricName));
            }
        }
        return fields;
    }

    private StructuredQuery parseExpression(String expression, String label) {
        try {
            return objectMapper.readValue(expression, StructuredQuery.class);
        } catch (JacksonException e) {
            log.warn("Skipping metric score '{}': unparseable expression: {}", label, e.getMessage(), e);
            return null;
        }
    }

    private MetricScoreResult buildResult(
            MetricScoreComputationContext ctx, String scoreName, String metricName, Double value) {
        return MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(ctx.getTestSuiteRunId())
                .computationId(ctx.getComputationId())
                .metricScoreName(scoreName)
                .metricName(metricName)
                .value(value)
                .build();
    }

    private static boolean isCancelled(MetricScoreComputationContext ctx) {
        return ctx.getCancellationSignal() != null
                && ctx.getCancellationSignal().get();
    }

    /** A flattened numeric metric column: its DSL field name ({@code metric:<tsmd>:<field>}) and the stored {@code metric_name}. */
    private record MetricField(String flattenedName, String metricName) {}
}
