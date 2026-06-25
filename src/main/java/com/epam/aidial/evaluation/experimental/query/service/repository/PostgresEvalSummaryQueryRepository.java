package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link EvalSummaryQueryRepository}: binds the {@code eval_summaries}
 * entity to the generated {@code TEST_CASE_EVAL_SUMMARIES} table on the analytics datasource
 * ({@code analyticsDsl}) and delegates all translation and execution to
 * {@link StructuredQueryExecutor} — mirroring {@link PostgresTestSuiteQueryRepository} with no
 * duplicated query plumbing.
 */
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresEvalSummaryQueryRepository implements EvalSummaryQueryRepository {

    private static final String ENTITY = "eval_summaries";

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final StructuredQueryExecutor executor;

    @Override
    public String supportedEntity() {
        return ENTITY;
    }

    @Override
    public QueryResultPage execute(StructuredQuery query) {
        return executor.execute(ENTITY, dsl, TEST_CASE_EVAL_SUMMARIES, query);
    }
}
