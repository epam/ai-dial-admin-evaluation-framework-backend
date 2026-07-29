package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreValueDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Recomputes the built-in metric-score statistics and the run-level {@code overall} over a <em>subset</em> of
 * a run's eval summaries, without persisting anything.
 *
 * <p>The whole component is one idea: run the queries Phase 3 itself runs, with a single row-exclusion
 * predicate ANDed onto each. {@link BuiltInMetricStatistics} and {@link OverallScoreDefinitionResolver} are
 * used unchanged, which is what makes agreement with the persisted full-population values structural rather
 * than merely tested — there is no second implementation that could drift.
 *
 * <p>The predicate is stated as an <strong>exclusion</strong>, {@code NOT (id IN (…))}, because two runs of
 * one suite over the same dataset match every row: the common case then excludes nothing, and
 * {@linkplain #exclusionPredicate(List) grafts no predicate at all}, so Phase 3's query runs verbatim and no
 * ids are bound. There is no {@code not_in} operator in the DSL and none is needed.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class FilteredMetricScoreAggregator {

    private static final String FIELD_ID = "id";

    private final BuiltInMetricStatistics builtInStatistics;
    private final OverallScoreDefinitionResolver overallScoreDefinitionResolver;
    private final StructuredQueryService structuredQueryService;

    /**
     * Computes every built-in statistic for every discovered metric field, plus {@code overall} when it
     * applies, over the run's rows minus {@code request.unmatchedEvalSummaryIds()}.
     *
     * <p>A statistic whose aggregate is SQL NULL is <strong>omitted</strong>, exactly as Phase 3 omits it, so
     * no returned entry carries a null value. A {@link ValidationException} on one statistic is logged and
     * skipped so it cannot abort the rest.
     *
     * @return the computed values; empty when the run discovered no metric fields
     */
    public List<MetricScoreValueDto> aggregate(FilteredMetricScoreRequest request) {
        if (request.metricFields().isEmpty()) {
            return List.of();
        }
        final FilterNode idPredicate = exclusionPredicate(request.unmatchedEvalSummaryIds());
        final List<MetricScoreValueDto> values = new ArrayList<>();

        for (final BuiltInMetricStatistics.MetricStatistic statistic : builtInStatistics.perMetric()) {
            values.addAll(computePerMetric(statistic, idPredicate, request));
        }
        computeOverall(idPredicate, request).ifPresent(values::add);
        return values;
    }

    /** Each built-in statistic, once per metric field, binding {@code :metricField} as Phase 3 does. */
    private List<MetricScoreValueDto> computePerMetric(
            BuiltInMetricStatistics.MetricStatistic statistic,
            FilterNode idPredicate,
            FilteredMetricScoreRequest request) {
        final StructuredQuery query = withIdPredicate(statistic.query(), idPredicate);
        final List<MetricScoreValueDto> values = new ArrayList<>();
        for (final MetricField metricField : request.metricFields()) {
            final Map<String, Expr> params = baseParams(request);
            params.put(MetricScoreConstants.PARAM_METRIC_FIELD, new FieldExpr(metricField.flattenedName()));
            final Double value = executeScalar(
                    query, params, MetricScoreConstants.VALUE_ALIAS, statistic.name(), metricField.flattenedName());
            if (value != null) {
                values.add(value(statistic.name(), metricField.metricName(), value));
            }
        }
        return values;
    }

    /**
     * The run-level {@code overall}, following Phase 3's own inclusion rule: a non-null definition is always
     * computed, a null definition only when the run has exactly one discovered field (then {@code overall} is
     * that metric's average).
     *
     * <p>Kept separate from the per-metric loop because a {@code CustomFunction} definition resolves to a
     * complete {@link StructuredQuery} rather than a liftable expression — which is also why
     * {@link OverallScoreDefinitionResolver} needs no changes here.
     */
    private Optional<MetricScoreValueDto> computeOverall(FilterNode idPredicate, FilteredMetricScoreRequest request) {
        final boolean isDefault = request.overallScoreDefinition() == null;
        if (isDefault && request.metricFields().size() != 1) {
            return Optional.empty();
        }
        final StructuredQuery resolved = isDefault
                ? builtInStatistics.defaultOverall()
                // The resolver MUST see the run's full discovered field list: a mean divides by its size, so
                // any filtered subset would silently change the divisor.
                : overallScoreDefinitionResolver.resolve(
                        request.overallScoreDefinition(), flattenedFieldNames(request.metricFields()));
        if (resolved == null) {
            // Unparseable custom_function; already logged by the resolver.
            return Optional.empty();
        }
        final String valueAlias = singleValueAlias(resolved);
        if (valueAlias == null) {
            return Optional.empty();
        }

        final Map<String, Expr> params = baseParams(request);
        if (isDefault) {
            params.put(
                    MetricScoreConstants.PARAM_METRIC_FIELD,
                    new FieldExpr(request.metricFields().getFirst().flattenedName()));
        }
        final Double value = executeScalar(
                withIdPredicate(resolved, idPredicate),
                params,
                valueAlias,
                MetricScoreConstants.SCORE_OVERALL,
                MetricScoreConstants.SCORE_OVERALL);
        return Optional.ofNullable(value)
                .map(v -> value(MetricScoreConstants.SCORE_OVERALL, MetricScoreConstants.SCORE_OVERALL, v));
    }

    /**
     * Returns a copy of {@code query} with {@code idPredicate} ANDed onto its filter, leaving every other
     * component untouched. A null {@code idPredicate} returns {@code query} itself, so the no-exclusion case
     * runs Phase 3's query verbatim.
     */
    StructuredQuery withIdPredicate(StructuredQuery query, FilterNode idPredicate) {
        if (idPredicate == null) {
            return query;
        }
        final FilterNode filter = query.filter() == null
                ? idPredicate
                : new LogicalNode(LogicalOp.AND, List.of(query.filter(), idPredicate));
        return new StructuredQuery(
                query.entity(),
                filter,
                query.mode(),
                query.distinct(),
                query.select(),
                query.groupBy(),
                query.having(),
                query.sort(),
                query.page());
    }

    /**
     * {@code NOT (id IN (…))} over the rows to leave out, or {@code null} when there are none.
     *
     * <p>Null rather than an empty {@code in} array on purpose: the translator rejects an empty array, and
     * grafting nothing is both cheaper and the case where agreement with the persisted values is a tautology.
     * Safe from the classic {@code NOT IN} null trap because {@code id} is a primary-key component and no
     * element of the list is null.
     */
    FilterNode exclusionPredicate(List<UUID> unmatchedEvalSummaryIds) {
        if (unmatchedEvalSummaryIds == null || unmatchedEvalSummaryIds.isEmpty()) {
            return null;
        }
        final List<Expr> items = unmatchedEvalSummaryIds.stream()
                .<Expr>map(id -> new ValueExpr(ValueType.UUID, id.toString()))
                .toList();
        final ComparisonNode membership =
                new ComparisonNode(ComparisonOp.IN, List.of(new FieldExpr(FIELD_ID), new ArrayExpr(items)));
        return new LogicalNode(LogicalOp.NOT, List.of(membership));
    }

    /**
     * The alias to read the scalar result under, or {@code null} if the query cannot yield one.
     *
     * <p>The built-in paths always produce a single {@link MetricScoreConstants#VALUE_ALIAS} column, but a
     * {@code CustomFunction} is stored opaquely and never validated as a runnable query, so its shape is
     * checked here and it may use an alias of its own.
     */
    private String singleValueAlias(StructuredQuery query) {
        if (!MetricScoreConstants.ENTITY_EVAL_SUMMARIES.equals(query.entity())) {
            log.warn(
                    "Skipping metric score 'overall': definition targets entity '{}', expected '{}'",
                    query.entity(),
                    MetricScoreConstants.ENTITY_EVAL_SUMMARIES);
            return null;
        }
        if (query.mode() != QueryMode.AGGREGATE) {
            log.warn("Skipping metric score 'overall': definition mode is {}, expected AGGREGATE", query.mode());
            return null;
        }
        final List<OutputColumn> select = query.select();
        if (select == null || select.size() != 1) {
            log.warn(
                    "Skipping metric score 'overall': definition selects {} columns, expected exactly 1",
                    select == null ? 0 : select.size());
            return null;
        }
        final String alias = select.getFirst().as();
        if (alias == null || alias.isBlank()) {
            log.warn("Skipping metric score 'overall': definition's select column has no alias");
            return null;
        }
        return alias;
    }

    private Double executeScalar(
            StructuredQuery query, Map<String, Expr> params, String valueAlias, String scoreName, String label) {
        try {
            final QueryResultPage page = structuredQueryService.execute(query, params);
            final List<Map<String, Object>> rows = page.rows();
            if (rows.isEmpty()) {
                return null;
            }
            final Object value = rows.getFirst().get(valueAlias);
            return value instanceof Number number ? number.doubleValue() : null;
        } catch (ValidationException e) {
            log.warn("Metric score '{}' over '{}' failed: {}", scoreName, label, e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Expr> baseParams(FilteredMetricScoreRequest request) {
        final Map<String, Expr> params = new HashMap<>();
        params.put(
                MetricScoreConstants.PARAM_RUN_ID,
                new ValueExpr(ValueType.UUID, request.runId().toString()));
        params.put(
                MetricScoreConstants.PARAM_COMPUTATION_ID,
                new ValueExpr(ValueType.UUID, request.computationId().toString()));
        return params;
    }

    private static List<String> flattenedFieldNames(List<MetricField> metricFields) {
        return metricFields.stream().map(MetricField::flattenedName).toList();
    }

    private static MetricScoreValueDto value(String scoreName, String metricName, Double value) {
        return MetricScoreValueDto.builder()
                .metricScoreName(scoreName)
                .metricName(metricName)
                .value(value)
                .build();
    }
}
