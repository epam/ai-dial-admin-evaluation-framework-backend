package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link TestSuiteQueryRepository}: binds the {@code test_suites} entity
 * to the generated {@code TEST_SUITES} table on the meta datasource ({@code metaDsl}) and delegates
 * all translation and execution to {@link StructuredQueryExecutor}.
 */
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteQueryRepository implements TestSuiteQueryRepository {

    private static final String ENTITY = "test_suites";

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final StructuredQueryExecutor executor;

    @Override
    public String supportedEntity() {
        return ENTITY;
    }

    @Override
    public QueryResultPage execute(StructuredQuery query) {
        return executor.execute(ENTITY, dsl, TEST_SUITES, query);
    }
}
