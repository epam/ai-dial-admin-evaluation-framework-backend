package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.COLUMN_SEPARATOR;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreDefinition;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreDefinitionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputation;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 3 of a test suite run: computes metric-score statistics by executing each applicable
 * {@link MetricScoreDefinition}'s persisted {@link StructuredQuery} via the Query DSL, and persists
 * the results. Reuses the run's metric-evaluation {@code computationId}, so the scores join that
 * computation.
 *
 * <p>Dispatch is driven by which {@code param} an expression references (no separate kind flag):
 * an expression using {@code :metricField} is executed once per metric field (one row per metric —
 * the per-metric statistics); an expression using {@code :metricAvgs} (or no metric param) is
 * executed once at run level (one row — e.g. {@code overall}, whose expression averages the array of
 * per-metric {@code avg(...)} terms bound to {@code :metricAvgs}).
 *
 * <p>Fault isolation: a failure on one (definition, field) pair is logged and skipped; the rest still
 * compute. The caller treats a Phase-3 failure as non-fatal to the run.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricScoreComputationExecutor implements MetricScoreComputation {

    private final MetricScoreDefinitionRepository definitionRepository;
    private final MetricScoreResultRepository resultRepository;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
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

        final List<MetricScoreDefinition> definitions = definitionRepository.findApplicable(ctx.getTestSuiteId());
        final ArrayExpr metricAvgs = metricAvgsArray(metricFields);

        final List<MetricScoreResult> results = new ArrayList<>();
        for (final MetricScoreDefinition definition : definitions) {
            if (isCancelled(ctx)) {
                return;
            }
            final StructuredQuery query = parseExpression(definition);
            if (query == null) {
                continue;
            }
            if (referencesParam(query, MetricScoreConstants.PARAM_METRIC_FIELD)) {
                computePerMetric(query, definition, metricFields, ctx, results);
            } else {
                computeRunLevel(query, definition, metricAvgs, ctx, results);
            }
        }

        resultRepository.saveAll(results);
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
            params.put(MetricScoreConstants.PARAM_METRIC_FIELD, new FieldExpr(metricField.token()));
            final Double value = executeScalar(query, params, definition.getName(), metricField.token(), ctx);
            if (value != null) {
                results.add(buildResult(ctx, definition.getName(), metricField.metricName(), value));
            }
        }
    }

    /** Run-level score (e.g. {@code overall}): run once, binding the per-metric {@code avg} array. */
    private void computeRunLevel(
            StructuredQuery query,
            MetricScoreDefinition definition,
            ArrayExpr metricAvgs,
            MetricScoreComputationContext ctx,
            List<MetricScoreResult> results) {
        final Map<String, Expr> params = baseParams(ctx);
        params.put(MetricScoreConstants.PARAM_METRIC_AVGS, metricAvgs);
        final Double value = executeScalar(query, params, definition.getName(), definition.getName(), ctx);
        if (value != null) {
            results.add(buildResult(ctx, definition.getName(), definition.getName(), value));
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
            avgs.add(new FnExpr(
                    MetricScoreConstants.STAT_AVG.toLowerCase(java.util.Locale.ROOT),
                    false,
                    List.of(new FieldExpr(metricField.token()))));
        }
        return new ArrayExpr(avgs);
    }

    private Double executeScalar(
            StructuredQuery query,
            Map<String, Expr> params,
            String definitionName,
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
                    definitionName,
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
                final String token = METRIC_COLUMN_PREFIX + snapshot.getTsmdName() + COLUMN_SEPARATOR + outputField;
                final String metricName = snapshot.getTsmdName() + "." + outputField;
                fields.add(new MetricField(token, metricName));
            }
        }
        return fields;
    }

    private StructuredQuery parseExpression(MetricScoreDefinition definition) {
        try {
            return objectMapper.readValue(definition.getExpression(), StructuredQuery.class);
        } catch (JacksonException e) {
            log.warn(
                    "Skipping metric score definition '{}' ({}): unparseable expression: {}",
                    definition.getName(),
                    definition.getId(),
                    e.getMessage(),
                    e);
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

    /** True if {@code query}'s select/filter/having reference a {@code param} with the given name. */
    private static boolean referencesParam(StructuredQuery query, String paramName) {
        final Set<String> names = new HashSet<>();
        if (query.select() != null) {
            for (final OutputColumn col : query.select()) {
                collectExprParams(col.expr(), names);
            }
        }
        collectFilterParams(query.filter(), names);
        collectFilterParams(query.having(), names);
        return names.contains(paramName);
    }

    private static void collectExprParams(Expr expr, Set<String> names) {
        switch (expr) {
            case null -> {
                /* nothing */
            }
            case ParamExpr param -> names.add(param.name());
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> collectExprParams(arg, names));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> collectExprParams(item, names));
                }
            }
            default -> {
                /* FieldExpr / ValueExpr have no params */
            }
        }
    }

    private static void collectFilterParams(FilterNode node, Set<String> names) {
        switch (node) {
            case null -> {
                /* nothing */
            }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(child -> collectFilterParams(child, names));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> collectExprParams(arg, names));
                }
            }
        }
    }

    /** A flattened numeric metric column: its DSL field token and the stored {@code metric_name}. */
    private record MetricField(String token, String metricName) {}
}
