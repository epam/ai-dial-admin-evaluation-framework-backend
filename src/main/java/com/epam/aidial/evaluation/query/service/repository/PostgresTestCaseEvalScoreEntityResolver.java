package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code test_case_eval_scores} entity to a {@code SELECT DISTINCT ON (test_suite_run_id,
 * test_case_id, computation_id) ... ORDER BY ..., computed_at_ms DESC} view over the generated
 * {@code TEST_CASE_EVAL_SCORES} table: one row per test case per computation (the freshest by
 * {@code computed_at_ms}), even though the underlying table still stores one row per raw
 * {@code test_case_eval_summaries} row (see {@code test-case-metric-score-aggregation}'s design for why
 * the table was extended in place rather than re-keyed). {@code eval_summary_id} is deliberately excluded
 * from the projection — which raw row's id "wins" the dedup is an implementation detail, not meaningful
 * to a client of this entity.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseEvalScoreEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "test_case_eval_scores";

    /** Public so {@code TestCaseEvalScoresSchemaProvider} (a different package) can derive its schema
     *  from the exact same field list without depending on this vendor-gated bean's own lifecycle. */
    public static final Table<?> DEDUPED = DSL.select(
                    TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID,
                    TEST_CASE_EVAL_SCORES.TEST_CASE_ID,
                    TEST_CASE_EVAL_SCORES.TEST_CASE_NAME,
                    TEST_CASE_EVAL_SCORES.COMPUTATION_ID,
                    TEST_CASE_EVAL_SCORES.SCORE,
                    TEST_CASE_EVAL_SCORES.PASSED,
                    TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS)
            .distinctOn(
                    TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID,
                    TEST_CASE_EVAL_SCORES.TEST_CASE_ID,
                    TEST_CASE_EVAL_SCORES.COMPUTATION_ID)
            .from(TEST_CASE_EVAL_SCORES)
            .orderBy(
                    TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID,
                    TEST_CASE_EVAL_SCORES.TEST_CASE_ID,
                    TEST_CASE_EVAL_SCORES.COMPUTATION_ID,
                    TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS.desc())
            .asTable("test_case_eval_scores_deduped");

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;
    private final MetricScoreLatestComputationDefaulter latestComputationDefaulter;

    public PostgresTestCaseEvalScoreEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            MetricScoreLatestComputationDefaulter latestComputationDefaulter) {
        this.dsl = dsl;
        this.bindings = schemaResolver.bindings(DEDUPED);
        this.latestComputationDefaulter = latestComputationDefaulter;
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
        return DEDUPED;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }

    @Override
    public StructuredQuery rewrite(StructuredQuery query) {
        return latestComputationDefaulter.resolveLatestComputation(query);
    }
}
