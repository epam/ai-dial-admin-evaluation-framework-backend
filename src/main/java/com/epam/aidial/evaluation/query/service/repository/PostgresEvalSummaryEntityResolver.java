package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code eval_summaries} entity to {@code TEST_CASE_EVAL_SUMMARIES} LEFT JOINed to a
 * narrowed projection of {@code TEST_CASE_EVAL_SCORES} on the analytics datasource
 * ({@code analyticsDsl}), exposing {@code score}/{@code passed} as ordinary queryable fields
 * alongside the base table's own columns. Only {@code eval_summary_id}/{@code score}/{@code passed}
 * plus an aliased {@code created_at_ms} are carried into the join — {@code test_case_eval_scores}'s
 * raw {@code computed_at_ms} and {@code created_at_ms} columns are deliberately excluded/aliased,
 * since {@code test_case_eval_summaries} already has its own columns of the same name and a
 * row-mode query with no explicit {@code select} selects every column of {@link #table()}; joining
 * the raw table (or projecting {@code created_at_ms} under its own name) would make the two
 * same-named columns ambiguous. {@code created_at_ms} is projected as {@code tces_created_at_ms}
 * purely so the join predicate can also match on it (both tables share the same partition key,
 * per {@code openspec/changes/partition-analytics-tables/design.md} D5a) without leaking a second
 * {@code created_at_ms} column into row-mode results. The join condition
 * ({@code eval_summary_id AND created_at_ms}) is {@code test_case_eval_scores}'s composite primary
 * key, so Postgres can — and does — eliminate the join entirely for any query that references
 * neither {@code score} nor {@code passed}; existing queries against this entity are unaffected.
 * Field bindings are static per-table metadata, computed once here.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresEvalSummaryEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "eval_summaries";
    private static final String SCORES_CREATED_AT_MS_ALIAS = "tces_created_at_ms";

    private static final Table<?> SCORES_JOIN = DSL.select(
                    TEST_CASE_EVAL_SCORES.EVAL_SUMMARY_ID,
                    TEST_CASE_EVAL_SCORES.SCORE,
                    TEST_CASE_EVAL_SCORES.PASSED,
                    TEST_CASE_EVAL_SCORES.CREATED_AT_MS.as(SCORES_CREATED_AT_MS_ALIAS))
            .from(TEST_CASE_EVAL_SCORES)
            .asTable("tces_scores");

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;

    public PostgresEvalSummaryEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl, JooqTableSchemaResolver schemaResolver) {
        this.dsl = dsl;
        Map<String, QueryFieldBinding> merged = new LinkedHashMap<>(schemaResolver.bindings(TEST_CASE_EVAL_SUMMARIES));
        merged.put(
                "score",
                new QueryFieldBinding("score", SCORES_JOIN.field(TEST_CASE_EVAL_SCORES.SCORE), QueryFieldType.DECIMAL));
        merged.put(
                "passed",
                new QueryFieldBinding(
                        "passed", SCORES_JOIN.field(TEST_CASE_EVAL_SCORES.PASSED), QueryFieldType.BOOLEAN));
        this.bindings = Map.copyOf(merged);
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
        return TEST_CASE_EVAL_SUMMARIES
                .leftJoin(SCORES_JOIN)
                .on(SCORES_JOIN
                        .field(TEST_CASE_EVAL_SCORES.EVAL_SUMMARY_ID)
                        .eq(TEST_CASE_EVAL_SUMMARIES.ID)
                        .and(SCORES_JOIN
                                .field(SCORES_CREATED_AT_MS_ALIAS, Long.class)
                                .eq(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS)));
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }
}
