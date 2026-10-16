package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.TestCaseEvalSummaries;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Opt-in support for resolving {@code computation_id eq "latest"} <em>in SQL, per run</em> on a
 * computation-versioned query entity, whichever way the filter selected the runs (literal list, {@code in}
 * subquery, or no run predicate at all). The single-run literal path
 * ({@link MetricScoreLatestComputationDefaulter}) stays first; this handles whatever sentinel survives it.
 *
 * <p>An entity adds {@link #latestBinding} to its bindings (via {@link #withLatest}) and runs
 * {@link #rewriteLatest} from its {@code rewrite} hook. The binding is a virtual BOOLEAN field that is
 * deliberately <strong>not</strong> a column of the entity's table, so it appears in neither the default
 * projection nor the published schema. "Latest" is the canonical definition shared with
 * {@code ComputationResolver}: the run's newest eval summary, {@code computed_at_ms DESC, computation_id ASC}.
 * Only {@code eq} is rewritten.
 */
@Component
@LogExecution
public class LatestComputationSupport {

    private static final String LATEST_ALIAS = "latest";

    /**
     * A BOOLEAN binding that is true when the row's computation is the latest one of the row's run. The
     * lookup goes against {@code test_case_eval_summaries} (not the entity's own table), so a run whose
     * newest computation has no rows for the entity contributes nothing rather than falling back.
     */
    public QueryFieldBinding latestBinding(Field<String> runIdField, Field<String> computationIdField) {
        final TestCaseEvalSummaries latest = TEST_CASE_EVAL_SUMMARIES.as(LATEST_ALIAS);
        final Field<String> latestComputationId = DSL.field(DSL.select(latest.COMPUTATION_ID)
                .from(latest)
                .where(latest.TEST_SUITE_RUN_ID.eq(runIdField))
                .orderBy(latest.COMPUTED_AT_MS.desc(), latest.COMPUTATION_ID.asc())
                .limit(1));
        return new QueryFieldBinding(
                MetricScoreConstants.FIELD_IS_LATEST_COMPUTATION,
                DSL.field(computationIdField.eq(latestComputationId)),
                QueryFieldType.BOOLEAN);
    }

    /** A new map holding {@code base} plus {@code latest}; {@code base} is not mutated. */
    public Map<String, QueryFieldBinding> withLatest(Map<String, QueryFieldBinding> base, QueryFieldBinding latest) {
        final Map<String, QueryFieldBinding> result = new HashMap<>(base);
        result.put(latest.name(), latest);
        return result;
    }

    /**
     * Replaces every {@code computation_id eq "latest"} (case-insensitive, at any depth of the filter tree)
     * with {@code is_latest_computation eq true}. Returns the same instance when nothing matched.
     */
    public StructuredQuery rewriteLatest(StructuredQuery query) {
        final FilterNode filter = query.filter();
        final FilterNode rewritten = rewrite(filter);
        return rewritten == filter ? query : MetricScoreLatestComputationDefaulter.withFilter(query, rewritten);
    }

    private static FilterNode rewrite(FilterNode node) {
        return switch (node) {
            case null -> null;
            case ComparisonNode comparison ->
                MetricScoreLatestComputationDefaulter.isLatestComputationEq(comparison)
                        ? isLatestComputationTrue()
                        : comparison;
            case LogicalNode logical -> rewriteLogical(logical);
        };
    }

    private static FilterNode rewriteLogical(LogicalNode logical) {
        final List<FilterNode> children =
                logical.args().stream().map(LatestComputationSupport::rewrite).toList();
        return anyReplaced(logical.args(), children) ? new LogicalNode(logical.op(), children) : logical;
    }

    /** Identity comparison: {@link #rewrite} returns the very same node when it changes nothing. */
    private static boolean anyReplaced(List<FilterNode> original, List<FilterNode> rewritten) {
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i) != rewritten.get(i)) {
                return true;
            }
        }
        return false;
    }

    private static ComparisonNode isLatestComputationTrue() {
        return new ComparisonNode(
                ComparisonOp.EQ,
                List.of(
                        new FieldExpr(MetricScoreConstants.FIELD_IS_LATEST_COMPUTATION),
                        new ValueExpr(ValueType.BOOLEAN, Boolean.TRUE.toString())));
    }
}
