package com.epam.aidial.evaluation.experimental.query.service.repository;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@code computation_id eq "latest"} sentinel for a {@code metric_score_results} query.
 * The unified Query DSL engine is computation-agnostic and would reject {@code "latest"} as an invalid
 * UUID literal, so for a query that pins a single run ({@code test_suite_run_id eq <uuid>}) this swaps
 * the sentinel for the run's latest computation id, resolved by {@link ComputationResolver} — the single
 * authority for "latest" (shared with the eval-summary read path).
 *
 * <p>Everything else passes through untouched: a real {@code computation_id eq <uuid>},
 * {@code computation_id in [...]}, an omitted {@code computation_id} (⇒ all computations), or a query
 * with no single-run filter. When the run has no eval summaries — the table {@link ComputationResolver}
 * resolves "latest" from — the sentinel is left in place and the engine rejects it (a run with no
 * eval summaries has no metric scores to return anyway).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricScoreLatestComputationDefaulter {

    private static final String LATEST = "latest";

    private final ComputationResolver computationResolver;

    public StructuredQuery resolveLatestComputation(StructuredQuery query) {
        final FilterNode filter = query.filter();
        if (filter == null) {
            return query;
        }
        final Optional<String> computation = singleEqValue(filter, MetricScoreConstants.FIELD_COMPUTATION_ID);
        if (computation.isEmpty() || !LATEST.equalsIgnoreCase(computation.get())) {
            return query; // omitted, a real UUID, or `in` → leave as-is
        }
        final Optional<String> runId = singleEqValue(filter, MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID);
        if (runId.isEmpty()) {
            return query; // no single run to resolve "latest" against; the engine validates the sentinel
        }
        return computationResolver
                .resolve(LATEST, UUID.fromString(runId.get()))
                .map(id -> withFilter(query, rewriteLatestComputation(filter, id.toString())))
                .orElse(query);
    }

    private static StructuredQuery withFilter(StructuredQuery query, FilterNode filter) {
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

    /** Replaces the value of the {@code computation_id eq "latest"} comparison with the resolved id. */
    private static FilterNode rewriteLatestComputation(FilterNode node, String resolvedId) {
        return switch (node) {
            case null -> null;
            case ComparisonNode comparison ->
                isLatestComputationEq(comparison)
                        ? new ComparisonNode(
                                comparison.op(),
                                List.of(comparison.args().get(0), new ValueExpr(ValueType.UUID, resolvedId)))
                        : comparison;
            case LogicalNode logical ->
                new LogicalNode(
                        logical.op(),
                        logical.args().stream()
                                .map(child -> rewriteLatestComputation(child, resolvedId))
                                .toList());
        };
    }

    private static boolean isLatestComputationEq(ComparisonNode comparison) {
        return comparison.op() == ComparisonOp.EQ
                && comparison.args().size() == 2
                && comparison.args().getFirst() instanceof FieldExpr field
                && MetricScoreConstants.FIELD_COMPUTATION_ID.equals(field.name())
                && comparison.args().get(1) instanceof ValueExpr value
                && value.value() != null
                && LATEST.equalsIgnoreCase(value.value());
    }

    /**
     * The literal value of a single, unambiguous {@code <fieldName> eq <value>} comparison in the tree, or
     * empty when there is no such comparison or more than one.
     */
    private static Optional<String> singleEqValue(FilterNode node, String fieldName) {
        final List<String> values = collectEqValues(node, fieldName);
        return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
    }

    private static List<String> collectEqValues(FilterNode node, String fieldName) {
        return switch (node) {
            case null -> List.of();
            case LogicalNode logical ->
                logical.args().stream()
                        .flatMap(child -> collectEqValues(child, fieldName).stream())
                        .toList();
            case ComparisonNode comparison ->
                eqValue(comparison, fieldName).map(List::of).orElseGet(List::of);
        };
    }

    private static Optional<String> eqValue(ComparisonNode comparison, String fieldName) {
        if (comparison.op() != ComparisonOp.EQ || comparison.args().size() != 2) {
            return Optional.empty();
        }
        final Expr left = comparison.args().get(0);
        final Expr right = comparison.args().get(1);
        if (left instanceof FieldExpr field
                && fieldName.equals(field.name())
                && right instanceof ValueExpr value
                && value.value() != null) {
            return Optional.of(value.value());
        }
        return Optional.empty();
    }
}
