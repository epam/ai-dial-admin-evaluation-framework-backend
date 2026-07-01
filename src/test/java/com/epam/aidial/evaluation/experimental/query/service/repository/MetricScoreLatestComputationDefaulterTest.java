package com.epam.aidial.evaluation.experimental.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricScoreLatestComputationDefaulterTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LATEST = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ComputationResolver computationResolver = mock(ComputationResolver.class);
    private final MetricScoreLatestComputationDefaulter defaulter =
            new MetricScoreLatestComputationDefaulter(computationResolver);

    @Test
    @DisplayName("computation_id eq \"latest\" is resolved to the run's latest computation id")
    void resolvesLatestSentinelForSingleRun() {
        when(computationResolver.resolve("latest", RUN_ID)).thenReturn(Optional.of(LATEST));
        final FilterNode filter =
                new LogicalNode(LogicalOp.AND, List.of(eqRun(RUN_ID), eqField("computation_id", "LATEST")));

        final StructuredQuery result = defaulter.resolveLatestComputation(query(filter));

        // The "latest" sentinel is swapped for the resolved computation id.
        assertThat(computationIdEqValues(result.filter())).containsExactly(LATEST.toString());
    }

    @Test
    @DisplayName("a real computation_id eq <uuid> is left untouched (resolver not consulted)")
    void leavesExplicitComputationUntouched() {
        final String explicit = UUID.randomUUID().toString();
        final FilterNode filter =
                new LogicalNode(LogicalOp.AND, List.of(eqRun(RUN_ID), eqField("computation_id", explicit)));
        final StructuredQuery query = query(filter);

        assertThat(defaulter.resolveLatestComputation(query)).isSameAs(query);
        verifyNoInteractions(computationResolver);
    }

    @Test
    @DisplayName("computation_id in [...] is left untouched")
    void leavesComputationInUntouched() {
        final FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eqRun(RUN_ID),
                        new ComparisonNode(
                                ComparisonOp.IN,
                                List.of(
                                        new FieldExpr("computation_id"),
                                        new ArrayExpr(List.of(
                                                new ValueExpr(
                                                        ValueType.UUID,
                                                        UUID.randomUUID().toString()),
                                                new ValueExpr(
                                                        ValueType.UUID,
                                                        UUID.randomUUID().toString())))))));
        final StructuredQuery query = query(filter);

        assertThat(defaulter.resolveLatestComputation(query)).isSameAs(query);
        verifyNoInteractions(computationResolver);
    }

    @Test
    @DisplayName("omitted computation_id is left untouched (spans all computations)")
    void leavesOmittedComputationUntouched() {
        final StructuredQuery query = query(eqRun(RUN_ID));

        assertThat(defaulter.resolveLatestComputation(query)).isSameAs(query);
        verifyNoInteractions(computationResolver);
    }

    @Test
    @DisplayName("\"latest\" without a single-run filter is left untouched (engine validates)")
    void leavesLatestSentinelWithoutRunUntouched() {
        final StructuredQuery query = query(eqField("computation_id", "latest"));

        assertThat(defaulter.resolveLatestComputation(query)).isSameAs(query);
        verifyNoInteractions(computationResolver);
    }

    @Test
    @DisplayName("\"latest\" for a run with no computations is left untouched (resolves to empty)")
    void leavesLatestSentinelUntouchedWhenNoComputations() {
        when(computationResolver.resolve("latest", RUN_ID)).thenReturn(Optional.empty());
        final FilterNode filter =
                new LogicalNode(LogicalOp.AND, List.of(eqRun(RUN_ID), eqField("computation_id", "latest")));
        final StructuredQuery query = query(filter);

        assertThat(defaulter.resolveLatestComputation(query)).isSameAs(query);
    }

    @Test
    @DisplayName("query with no filter at all is left untouched")
    void leavesFilterlessQueryUntouched() {
        final StructuredQuery query = query(null);

        assertThat(defaulter.resolveLatestComputation(query)).isSameAs(query);
        verifyNoInteractions(computationResolver);
    }

    private static StructuredQuery query(FilterNode filter) {
        return new StructuredQuery(
                "metric_score_results", filter, QueryMode.ROW, false, List.of(), null, null, null, null);
    }

    /** The values of all {@code computation_id eq <value>} comparisons in the tree. */
    private static List<String> computationIdEqValues(FilterNode node) {
        return switch (node) {
            case null -> List.of();
            case LogicalNode logical ->
                logical.args().stream()
                        .flatMap(child -> computationIdEqValues(child).stream())
                        .toList();
            case ComparisonNode comparison -> {
                if (comparison.op() == ComparisonOp.EQ
                        && comparison.args().get(0) instanceof FieldExpr field
                        && "computation_id".equals(field.name())
                        && comparison.args().get(1) instanceof ValueExpr value) {
                    yield List.of(value.value());
                }
                yield List.of();
            }
        };
    }

    private static ComparisonNode eqRun(UUID runId) {
        return eqField("test_suite_run_id", runId.toString());
    }

    private static ComparisonNode eqField(String field, String value) {
        final Expr literal = new ValueExpr(ValueType.UUID, value);
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), literal));
    }
}
