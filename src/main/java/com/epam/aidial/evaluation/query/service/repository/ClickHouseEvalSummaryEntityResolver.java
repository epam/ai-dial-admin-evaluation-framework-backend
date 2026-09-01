package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresEvalSummaryEntityResolver}. The Postgres resolver's body is
 * trivial per-table metadata (same entity name, same bindings) with no Postgres-specific SQL of its
 * own, so this class extends it rather than duplicating it — {@code entity()} and {@code rewrite()}
 * are inherited unchanged, and only the {@code analyticsDsl} qualifier, the vendor gate and the
 * generated model differ.
 *
 * <p>{@link #table()} and {@link #bindings} are re-derived from the ClickHouse-generated
 * {@code TEST_CASE_EVAL_SUMMARIES} so the ClickHouse query path is described by the ClickHouse
 * model end to end. The two models are held column-for-column identical by
 * {@code AnalyticsModelParityTest}, so the binding map is equivalent to the Postgres twin's.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseEvalSummaryEntityResolver extends PostgresEvalSummaryEntityResolver {

    private final Map<String, QueryFieldBinding> bindings;

    public ClickHouseEvalSummaryEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl, JooqTableSchemaResolver schemaResolver) {
        super(dsl, schemaResolver);
        this.bindings = schemaResolver.bindings(TEST_CASE_EVAL_SUMMARIES);
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
