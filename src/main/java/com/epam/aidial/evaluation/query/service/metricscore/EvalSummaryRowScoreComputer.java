package com.epam.aidial.evaluation.query.service.metricscore;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Computes a per-row overall score for a batch of {@code EvalSummary} row ids, reusing
 * {@link OverallScoreDefinitionResolver}'s output as-is: the run/computation-scoped
 * {@link StructuredQuery} it produces for {@code Mean}/{@code WeightedMean}/{@code CustomFunction}
 * alike is grafted with an {@code id IN (:rowIds)} filter and a {@code GROUP BY id}, turning the
 * run-level aggregate into one row per id, in a single query per batch.
 *
 * <p>Deliberately not persistence-aware and not an extension of {@link FilteredMetricScoreAggregator}
 * — that component's own contract is scoped to read-only what-if recomputation over the run's full
 * population minus an exclusion set; this one groups an inclusion set into per-row results for a
 * caller that will persist them.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class EvalSummaryRowScoreComputer {

    private static final String FIELD_ID = "id";

    private final OverallScoreDefinitionResolver overallScoreDefinitionResolver;
    private final StructuredQueryService structuredQueryService;

    /**
     * Computes a score for every {@code rowIds} entry the grouped query returns a result for — including
     * one whose aggregate is itself SQL {@code NULL} (e.g. a population-dependent function like
     * {@code roc_auc} degenerating on a single-row group, a degenerate-but-correct outcome). An id absent
     * from the returned map means no row was computed for it: {@code definition} is {@code null}, an
     * unparseable {@code CustomFunction} (already logged by the resolver), or a resolved query whose
     * shape cannot be safely grafted with a per-row group (logged here).
     */
    public Map<UUID, Double> computeBatch(
            OverallScoreDefinition definition,
            List<String> metricFieldNames,
            UUID runId,
            UUID computationId,
            List<UUID> rowIds) {
        if (definition == null || rowIds.isEmpty()) {
            return Map.of();
        }
        StructuredQuery resolved = overallScoreDefinitionResolver.resolve(definition, metricFieldNames);
        if (resolved == null) {
            return Map.of();
        }
        String valueAlias = requireGroupableShape(resolved);
        if (valueAlias == null) {
            return Map.of();
        }
        StructuredQuery grouped = groupById(resolved, rowIds);

        QueryResultPage page = structuredQueryService.execute(grouped, runAndComputationIdParams(runId, computationId));
        Map<UUID, Double> result = new HashMap<>();
        for (Map<String, Object> row : page.rows()) {
            Object idValue = row.get(FIELD_ID);
            if (idValue == null) {
                continue;
            }
            UUID id = UUID.fromString(String.valueOf(idValue));
            Object value = row.get(valueAlias);
            result.put(id, value instanceof Number number ? number.doubleValue() : null);
        }
        return result;
    }

    /**
     * {@code entity == "eval_summaries"}, {@code mode == AGGREGATE}, exactly one aliased select column,
     * and no pre-existing {@code groupBy} — a {@code CustomFunction} that already specifies its own
     * {@code groupBy} is rejected rather than having it silently overwritten. Returns the value alias, or
     * {@code null} (logged) when the shape can't be grafted.
     */
    private String requireGroupableShape(StructuredQuery query) {
        if (!MetricScoreConstants.ENTITY_EVAL_SUMMARIES.equals(query.entity())) {
            log.warn(
                    "Skipping per-row score: definition targets entity '{}', expected '{}'",
                    query.entity(),
                    MetricScoreConstants.ENTITY_EVAL_SUMMARIES);
            return null;
        }
        if (query.mode() != QueryMode.AGGREGATE) {
            log.warn("Skipping per-row score: definition mode is {}, expected AGGREGATE", query.mode());
            return null;
        }
        List<OutputColumn> select = query.select();
        if (select == null || select.size() != 1) {
            log.warn(
                    "Skipping per-row score: definition selects {} columns, expected exactly 1",
                    select == null ? 0 : select.size());
            return null;
        }
        String alias = select.getFirst().as();
        if (alias == null || alias.isBlank()) {
            log.warn("Skipping per-row score: definition's select column has no alias");
            return null;
        }
        if (query.groupBy() != null && !query.groupBy().isEmpty()) {
            log.warn(
                    "Skipping per-row score: definition already specifies groupBy {}, cannot graft per-row grouping",
                    query.groupBy());
            return null;
        }
        return alias;
    }

    /** Adds {@code id} to the select list, sets {@code groupBy = [id]}, ANDs {@code id IN (:rowIds)}. */
    private StructuredQuery groupById(StructuredQuery query, List<UUID> rowIds) {
        List<OutputColumn> select = new ArrayList<>();
        select.add(new OutputColumn(new FieldExpr(FIELD_ID), FIELD_ID));
        select.addAll(query.select());

        FilterNode idPredicate = idInPredicate(rowIds);
        FilterNode filter = query.filter() == null
                ? idPredicate
                : new LogicalNode(LogicalOp.AND, List.of(query.filter(), idPredicate));

        return new StructuredQuery(
                query.entity(),
                filter,
                query.mode(),
                query.distinct(),
                select,
                List.of(FIELD_ID),
                query.having(),
                query.sort(),
                query.page());
    }

    private FilterNode idInPredicate(List<UUID> rowIds) {
        List<Expr> items = rowIds.stream()
                .<Expr>map(id -> new ValueExpr(ValueType.UUID, id.toString()))
                .toList();
        return new ComparisonNode(ComparisonOp.IN, List.of(new FieldExpr(FIELD_ID), new ArrayExpr(items)));
    }

    private Map<String, Expr> runAndComputationIdParams(UUID runId, UUID computationId) {
        Map<String, Expr> params = new HashMap<>();
        params.put(MetricScoreConstants.PARAM_RUN_ID, new ValueExpr(ValueType.UUID, runId.toString()));
        params.put(MetricScoreConstants.PARAM_COMPUTATION_ID, new ValueExpr(ValueType.UUID, computationId.toString()));
        return params;
    }
}
