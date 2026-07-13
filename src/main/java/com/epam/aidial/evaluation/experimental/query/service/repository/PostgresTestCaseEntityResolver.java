package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.TestCaseFieldBindingsBuilder;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the complex {@code test_cases} entity to the generated {@code TEST_CASES} table on the meta
 * datasource ({@code metaDsl}). Because the flattened {@code data::<field>} typing is dataset-specific,
 * {@link #bindings} requires the query to carry a {@code dataset_id} equality filter (used both to
 * scope the returned rows and to build typed field bindings) and builds instance bindings via
 * {@link TestCaseFieldBindingsBuilder}. A missing or non-UUID {@code dataset_id} filter is rejected as
 * a client error (HTTP 400).
 */
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "test_cases";
    private static final String DATASET_ID_FIELD = "dataset_id";

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestCaseFieldBindingsBuilder bindingsBuilder;

    @Override
    public String entity() {
        return ENTITY;
    }

    @Override
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public Table<?> table() {
        return TEST_CASES;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindingsBuilder.build(requireDatasetId(query));
    }

    /**
     * Extracts the mandatory {@code dataset_id} equality from the query filter. Only a top-level
     * conjunct ({@code =} directly, or nested under {@code and}) is honored — a {@code dataset_id}
     * buried under {@code or}/{@code not} would not reliably scope the rows, so it is not accepted.
     */
    private static UUID requireDatasetId(StructuredQuery query) {
        final String raw = query == null ? null : findDatasetIdEquality(query.filter());
        if (raw == null) {
            throw new ValidationException(
                    "A '" + ENTITY + "' query must include a top-level '" + DATASET_ID_FIELD + "' equality filter");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("The '" + DATASET_ID_FIELD + "' filter value must be a UUID");
        }
    }

    private static String findDatasetIdEquality(FilterNode node) {
        return switch (node) {
            case null -> null;
            case ComparisonNode comparison -> datasetIdFromComparison(comparison);
            case LogicalNode logical -> datasetIdFromLogical(logical);
        };
    }

    private static String datasetIdFromLogical(LogicalNode node) {
        if (node.op() != LogicalOp.AND || node.args() == null) {
            return null;
        }
        for (final FilterNode child : node.args()) {
            final String found = findDatasetIdEquality(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String datasetIdFromComparison(ComparisonNode node) {
        if (node.op() != ComparisonOp.EQ || node.args() == null || node.args().size() != 2) {
            return null;
        }
        final Expr left = node.args().get(0);
        final Expr right = node.args().get(1);
        final String fromLeftField = datasetIdValue(left, right);
        return fromLeftField != null ? fromLeftField : datasetIdValue(right, left);
    }

    /** Returns the literal value when {@code fieldSide} is the {@code dataset_id} field and {@code valueSide} a literal. */
    private static String datasetIdValue(Expr fieldSide, Expr valueSide) {
        if (fieldSide instanceof FieldExpr field
                && DATASET_ID_FIELD.equals(field.name())
                && valueSide instanceof ValueExpr value) {
            return value.value();
        }
        return null;
    }
}
