package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.SubqueryExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LatestComputationSupport")
class LatestComputationSupportTest {

    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";

    private final LatestComputationSupport support = new LatestComputationSupport();

    private static final ComparisonNode IS_LATEST = new ComparisonNode(
            ComparisonOp.EQ, List.of(new FieldExpr("is_latest_computation"), new ValueExpr(ValueType.BOOLEAN, "true")));

    @Test
    @DisplayName("a bare computation_id eq \"latest\" becomes is_latest_computation eq true")
    void rewritesBareSentinel() {
        final StructuredQuery result = support.rewriteLatest(query(eq("computation_id", "latest")));

        assertThat(result.filter()).isEqualTo(IS_LATEST);
    }

    @Test
    @DisplayName("the sentinel is matched case-insensitively")
    void rewritesCaseInsensitiveSentinel() {
        final StructuredQuery result = support.rewriteLatest(query(eq("computation_id", "LaTeSt")));

        assertThat(result.filter()).isEqualTo(IS_LATEST);
    }

    @Test
    @DisplayName("a sentinel nested in and keeps its siblings, including an in-subquery run predicate")
    void rewritesNestedInAndPreservingSiblings() {
        final ComparisonNode runsInSubquery = new ComparisonNode(
                ComparisonOp.IN,
                List.of(
                        new FieldExpr("test_suite_run_id"),
                        new SubqueryExpr(new StructuredQuery(
                                "eval_summaries", null, QueryMode.ROW, false, null, null, null, null, null))));
        final ComparisonNode status = eq("execution_status", "SUCCESS");
        final FilterNode filter =
                new LogicalNode(LogicalOp.AND, List.of(runsInSubquery, eq("computation_id", "latest"), status));

        final StructuredQuery result = support.rewriteLatest(query(filter));

        assertThat(result.filter())
                .isEqualTo(new LogicalNode(LogicalOp.AND, List.of(runsInSubquery, IS_LATEST, status)));
    }

    @Test
    @DisplayName("a sentinel under or and not is rewritten in place")
    void rewritesUnderOrAndNot() {
        final FilterNode filter = new LogicalNode(
                LogicalOp.OR,
                List.of(
                        eq("execution_status", "ERROR"),
                        new LogicalNode(LogicalOp.NOT, List.of(eq("computation_id", "latest")))));

        final StructuredQuery result = support.rewriteLatest(query(filter));

        assertThat(result.filter())
                .isEqualTo(new LogicalNode(
                        LogicalOp.OR,
                        List.of(eq("execution_status", "ERROR"), new LogicalNode(LogicalOp.NOT, List.of(IS_LATEST)))));
    }

    @Test
    @DisplayName("queries without a rewritable sentinel are returned as the same instance")
    void leavesOtherQueriesUntouched() {
        final List<StructuredQuery> untouched = List.of(
                query(eq("computation_id", "22222222-2222-2222-2222-222222222222")),
                query(new ComparisonNode(
                        ComparisonOp.IN,
                        List.of(
                                new FieldExpr("computation_id"),
                                new ArrayExpr(List.of(new ValueExpr(ValueType.STRING, "latest")))))),
                query(new ComparisonNode(
                        ComparisonOp.NE,
                        List.of(new FieldExpr("computation_id"), new ValueExpr(ValueType.STRING, "latest")))),
                query(eq("test_suite_run_id", RUN_ID)),
                query(null));

        assertThat(untouched)
                .allSatisfy(query -> assertThat(support.rewriteLatest(query)).isSameAs(query));
    }

    @Test
    @DisplayName("the latest binding is a BOOLEAN field named is_latest_computation")
    void bindingIsBoolean() {
        final QueryFieldBinding binding = latestBinding();

        assertThat(binding.name()).isEqualTo("is_latest_computation");
        assertThat(binding.type()).isEqualTo(QueryFieldType.BOOLEAN);
    }

    @Test
    @DisplayName("withLatest returns a new map containing the binding and leaves the input unchanged")
    void withLatestDoesNotMutateInput() {
        final Map<String, QueryFieldBinding> base = new HashMap<>();
        final QueryFieldBinding latest = latestBinding();

        final Map<String, QueryFieldBinding> result = support.withLatest(base, latest);

        assertThat(base).isEmpty();
        assertThat(result).containsEntry("is_latest_computation", latest);
    }

    @Test
    @DisplayName("the binding SQL picks the newest summary: computed_at_ms desc, computation_id asc, one row")
    void bindingPinsCanonicalLatestOrdering() {
        final String sql = DSL.using(SQLDialect.POSTGRES)
                .renderInlined(latestBinding().field())
                .replace("\"", "")
                .toLowerCase();

        assertThat(sql)
                .contains("order by latest.computed_at_ms desc, latest.computation_id asc")
                .containsPattern("(limit|fetch next) 1( rows only)?\\)");
    }

    private QueryFieldBinding latestBinding() {
        return support.latestBinding(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID, TEST_CASE_EVAL_SCORES.COMPUTATION_ID);
    }

    private static StructuredQuery query(FilterNode filter) {
        return new StructuredQuery("test_case_eval_scores", filter, QueryMode.ROW, false, null, null, null, null, null);
    }

    private static ComparisonNode eq(String field, String value) {
        return new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(ValueType.STRING, value)));
    }
}
