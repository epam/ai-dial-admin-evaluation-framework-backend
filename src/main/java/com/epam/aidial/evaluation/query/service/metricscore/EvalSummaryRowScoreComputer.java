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
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.translate.StructuredQueryBuilder;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Computes a per-test-case overall score for a batch's distinct test case ids, against the
 * {@code test_case_metric_scores} entity (see {@code test-case-metric-score-aggregation}) rather than
 * raw {@code eval_summaries} rows — so a metric's partial presence across a test case's rows no longer
 * skews its score, and every {@code EvalSummary} row of that test case shares the identical result.
 *
 * <p>Only {@link Mean}/{@link WeightedMean} are meaningful per test case: {@link
 * OverallScoreDefinitionResolver} builds their query directly against {@code test_case_metric_scores} (we
 * author these queries ourselves, so there's nothing to retarget), and this class grafts
 * {@code test_case_id IN (:ids)} / {@code GROUP BY test_case_id} onto it to turn the run-level aggregate
 * into one value per test case — the same graft shape used for {@code CustomFunction} at Phase 3, just
 * scoped to a batch instead of the whole run. {@link CustomFunction} is rejected as a
 * {@code testCaseOverallScore} by {@code TestSuiteRequestValidator} at suite create/update time (a
 * population-dependent function like {@code roc_auc} is meaningless for a single test case, and {@code
 * Mean}/{@code WeightedMean} already cover every metric-only case) — the branch below is defensive only
 * and should be unreachable.
 *
 * <p>Deliberately not persistence-aware and not an extension of {@link FilteredMetricScoreAggregator}
 * — that component's own contract is scoped to read-only what-if recomputation over the run's full
 * population minus an exclusion set; this one groups an inclusion set into per-test-case results for a
 * caller that will persist them.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class EvalSummaryRowScoreComputer {

    private final OverallScoreDefinitionResolver overallScoreDefinitionResolver;
    private final StructuredQueryService structuredQueryService;

    /**
     * Computes a score for every {@code testCaseId} the grouped query yields a result for — including one
     * whose aggregate is itself SQL {@code NULL}. A test case id absent from the returned map means no
     * result was computed for it: {@code definition} is {@code null}, or the resolved query's shape
     * cannot be safely grafted (logged here).
     *
     * <p>{@code testCaseIds} is chunked to {@link StructuredQueryBuilder#MAX_LIMIT} per query — the
     * translator clamps any {@code OffsetPage} limit to that cap, so a single ungrafted query over a
     * larger batch would silently drop the excess rows rather than fail.
     */
    public Map<UUID, Double> computeByTestCase(
            OverallScoreDefinition definition,
            List<MetricField> metricFields,
            MetricScoreAggregation aggregation,
            UUID runId,
            UUID computationId,
            List<UUID> testCaseIds) {
        if (definition == null || testCaseIds.isEmpty()) {
            return Map.of();
        }
        if (definition instanceof CustomFunction) {
            log.warn(
                    "testCaseOverallScore is a CustomFunction for run {} computation {}; rejected at suite "
                            + "validation time, this branch should be unreachable — skipping per-test-case score",
                    runId,
                    computationId);
            return Map.of();
        }

        final StructuredQuery resolved =
                overallScoreDefinitionResolver.resolve(definition, metricKeys(metricFields), aggregation);
        final String valueAlias = requireGroupableShape(resolved);
        if (valueAlias == null) {
            return Map.of();
        }

        final Map<UUID, Double> result = new HashMap<>();
        for (int offset = 0; offset < testCaseIds.size(); offset += StructuredQueryBuilder.MAX_LIMIT) {
            final int end = Math.min(offset + StructuredQueryBuilder.MAX_LIMIT, testCaseIds.size());
            final List<UUID> chunk = testCaseIds.subList(offset, end);
            final StructuredQuery grouped = groupByTestCaseId(resolved, chunk);
            final QueryResultPage page =
                    structuredQueryService.execute(grouped, runAndComputationIdParams(runId, computationId));
            for (final Map<String, Object> row : page.rows()) {
                final Object idValue = row.get(MetricScoreConstants.FIELD_TEST_CASE_ID);
                if (idValue == null) {
                    continue;
                }
                final UUID testCaseId = UUID.fromString(String.valueOf(idValue));
                final Object value = row.get(valueAlias);
                result.put(testCaseId, value instanceof Number number ? number.doubleValue() : null);
            }
        }
        return result;
    }

    private static List<String> metricKeys(List<MetricField> metricFields) {
        return metricFields.stream().map(MetricField::metricName).toList();
    }

    /**
     * {@code entity == "test_case_metric_scores"}, {@code mode == AGGREGATE}, exactly one aliased select
     * column, and no pre-existing {@code groupBy}. {@code Mean}/{@code WeightedMean} always resolve to
     * this exact shape (built directly against the entity by {@link OverallScoreDefinitionResolver}), so
     * this check is defensive rather than load-bearing. Returns the value alias, or {@code null} (logged)
     * when the shape can't be grafted.
     */
    private String requireGroupableShape(StructuredQuery query) {
        if (!MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES.equals(query.entity())) {
            log.warn(
                    "Skipping per-test-case score: definition targets entity '{}', expected '{}'",
                    query.entity(),
                    MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES);
            return null;
        }
        if (query.mode() != QueryMode.AGGREGATE) {
            log.warn("Skipping per-test-case score: definition mode is {}, expected AGGREGATE", query.mode());
            return null;
        }
        List<OutputColumn> select = query.select();
        if (select == null || select.size() != 1) {
            log.warn(
                    "Skipping per-test-case score: definition selects {} columns, expected exactly 1",
                    select == null ? 0 : select.size());
            return null;
        }
        String alias = select.getFirst().as();
        if (alias == null || alias.isBlank()) {
            log.warn("Skipping per-test-case score: definition's select column has no alias");
            return null;
        }
        if (query.groupBy() != null && !query.groupBy().isEmpty()) {
            log.warn(
                    "Skipping per-test-case score: definition already specifies groupBy {}, cannot graft "
                            + "per-test-case grouping",
                    query.groupBy());
            return null;
        }
        return alias;
    }

    /**
     * Adds {@code test_case_id} to the select list, sets {@code groupBy = [test_case_id]}, ANDs
     * {@code test_case_id IN (:testCaseIds)}, and sets an explicit page sized to {@code testCaseIds} —
     * one row per grouped test case — rather than passing the resolved query's own {@code page}
     * (typically {@code null}) through unchanged; the translator defaults a {@code null} page to a
     * 100-row limit, which would silently truncate a larger batch's results with no {@code ORDER BY} to
     * make the truncation deterministic. Callers are responsible for keeping {@code testCaseIds.size()}
     * within {@link StructuredQueryBuilder#MAX_LIMIT}.
     */
    private StructuredQuery groupByTestCaseId(StructuredQuery query, List<UUID> testCaseIds) {
        List<OutputColumn> select = new ArrayList<>();
        select.add(new OutputColumn(
                new FieldExpr(MetricScoreConstants.FIELD_TEST_CASE_ID), MetricScoreConstants.FIELD_TEST_CASE_ID));
        select.addAll(query.select());

        FilterNode idPredicate = fieldInPredicate(MetricScoreConstants.FIELD_TEST_CASE_ID, testCaseIds);
        FilterNode filter = query.filter() == null
                ? idPredicate
                : new LogicalNode(LogicalOp.AND, List.of(query.filter(), idPredicate));

        return new StructuredQuery(
                query.entity(),
                filter,
                query.mode(),
                query.distinct(),
                select,
                List.of(MetricScoreConstants.FIELD_TEST_CASE_ID),
                query.having(),
                query.sort(),
                new OffsetPage(0, testCaseIds.size(), false));
    }

    private FilterNode fieldInPredicate(String field, List<UUID> ids) {
        List<Expr> items = ids.stream()
                .<Expr>map(id -> new ValueExpr(ValueType.UUID, id.toString()))
                .toList();
        return new ComparisonNode(ComparisonOp.IN, List.of(new FieldExpr(field), new ArrayExpr(items)));
    }

    private Map<String, Expr> runAndComputationIdParams(UUID runId, UUID computationId) {
        Map<String, Expr> params = new HashMap<>();
        params.put(MetricScoreConstants.PARAM_RUN_ID, new ValueExpr(ValueType.UUID, runId.toString()));
        params.put(MetricScoreConstants.PARAM_COMPUTATION_ID, new ValueExpr(ValueType.UUID, computationId.toString()));
        return params;
    }
}
