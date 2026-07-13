package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code test_suites} entity to the generated {@code TEST_SUITES} table on the meta
 * datasource ({@code metaDsl}). Field bindings are static per-table metadata, computed once here.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "test_suites";

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;

    public PostgresTestSuiteEntityResolver(
            @Qualifier("metaDsl") DSLContext dsl, JooqTableSchemaResolver schemaResolver) {
        this.dsl = dsl;
        this.bindings = schemaResolver.bindings(TEST_SUITES);
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
        return TEST_SUITES;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }
}
