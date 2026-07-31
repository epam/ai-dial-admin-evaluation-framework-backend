package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code eval_summaries} entity to the generated {@code TEST_CASE_EVAL_SUMMARIES} table
 * on the analytics datasource ({@code analyticsDsl}). Field bindings are static per-table metadata,
 * computed once here.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresEvalSummaryEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "eval_summaries";

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;

    public PostgresEvalSummaryEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl, JooqTableSchemaResolver schemaResolver) {
        this.dsl = dsl;
        this.bindings = schemaResolver.bindings(TEST_CASE_EVAL_SUMMARIES);
    }

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
        return TEST_CASE_EVAL_SUMMARIES;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }
}
