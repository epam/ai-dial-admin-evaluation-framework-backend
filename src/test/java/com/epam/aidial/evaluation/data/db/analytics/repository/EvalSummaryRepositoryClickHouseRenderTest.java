package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.TEST_CASE_EVAL_SUMMARIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.analytics.mapper.EvalSummaryRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricPath;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import java.math.BigDecimal;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Render probes (no DB) for the repository-shaped jOOQ constructs used by the analytics
 * repositories, on {@code SQLDialect.CLICKHOUSE}. Establishes, without a live container, which of
 * today's Postgres repository constructs render valid ClickHouse SQL unchanged (inherited as-is by
 * the ClickHouse repositories) and which do not (requiring the overrides in {@link
 * ClickHouseEvalSummaryRepository}).
 */
class EvalSummaryRepositoryClickHouseRenderTest {

    private final DSLContext dsl = DSL.using(SQLDialect.CLICKHOUSE);

    @Test
    @DisplayName("a keyset-page row-value tuple compare renders as a valid ClickHouse tuple comparison, "
            + "inherited unchanged")
    void keysetRowCompareRendersUnchanged() {
        String sql = dsl.renderInlined(DSL.row(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS, TEST_CASE_EVAL_SUMMARIES.ID)
                .lt(DSL.row(123L, "abc")));
        assertThat(sql)
                .isEqualTo("(\"analytics\".\"test_case_eval_summaries\".\"created_at_ms\", "
                        + "\"analytics\".\"test_case_eval_summaries\".\"id\") < (123, 'abc')");
    }

    @Test
    @DisplayName("a saveAll-shaped insert without onConflict renders a plain INSERT with no ON CONFLICT clause")
    void plainInsertRendersWithoutOnConflict() {
        String sql = dsl.renderInlined(dsl.insertInto(TEST_CASE_EVAL_SUMMARIES)
                .set(TEST_CASE_EVAL_SUMMARIES.ID, "id1")
                .set(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS, 1L));
        assertThat(sql)
                .isEqualTo("insert into \"analytics\".\"test_case_eval_summaries\" (\"id\", \"created_at_ms\") "
                        + "values ('id1', 1)")
                .doesNotContainIgnoringCase("on conflict");
    }

    @Test
    @DisplayName("fetchExists renders a scalar EXISTS subquery, which ClickHouse supports; inherited unchanged")
    void fetchExistsRendersScalarExistsSubquery() {
        String sql = dsl.renderInlined(DSL.exists(dsl.selectOne()
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq("r1"))));
        assertThat(sql)
                .isEqualTo("exists (select 1 \"one\" from \"analytics\".\"test_case_eval_summaries\" "
                        + "where \"analytics\".\"test_case_eval_summaries\".\"test_suite_run_id\" = 'r1')");
    }

    @Test
    @DisplayName("jOOQ's .filterWhere(...) still renders the standard-SQL FILTER (WHERE ...) clause on "
            + "SQLDialect.CLICKHOUSE, which ClickHouse does not support — why countMatches is overridden")
    void filterWhereRendersInvalidClickHouseSyntax() {
        String sql = dsl.renderInlined(dsl.select(DSL.count()
                        .filterWhere(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS.eq("SUCCESS"))
                        .as("matched"))
                .from(TEST_CASE_EVAL_SUMMARIES));
        assertThat(sql).contains("filter (where");
    }

    @Test
    @DisplayName("the CASE WHEN override for matchedSuccessRowsField renders count(case when ... then 1 end)")
    void caseWhenMatchedSuccessRowsOverride() {
        ClickHouseEvalSummaryRepository repository =
                new ClickHouseEvalSummaryRepository(dsl, mock(EvalSummaryRecordMapper.class), mock(WhereBuilder.class));
        Condition matched = TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq("r1");

        Field<Integer> field = repository.matchedSuccessRowsField(matched);

        assertThat(dsl.renderInlined(field))
                .isEqualTo("count(case when (\"analytics\".\"test_case_eval_summaries\".\"test_suite_run_id\" = "
                        + "'r1' and \"analytics\".\"test_case_eval_summaries\".\"execution_status\" = 'SUCCESS') "
                        + "then 1 end)")
                .doesNotContainIgnoringCase("filter (where");
    }

    @Test
    @DisplayName("the CASE WHEN override for avgExecDurationMsField renders avg(case when ... then x end)")
    void caseWhenAvgExecDurationOverride() {
        ClickHouseEvalSummaryRepository repository =
                new ClickHouseEvalSummaryRepository(dsl, mock(EvalSummaryRecordMapper.class), mock(WhereBuilder.class));
        Condition matched = TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq("r1");

        Field<BigDecimal> field = repository.avgExecDurationMsField(matched);

        assertThat(dsl.renderInlined(field))
                .isEqualTo("avg(case when \"analytics\".\"test_case_eval_summaries\".\"test_suite_run_id\" = "
                        + "'r1' then \"analytics\".\"test_case_eval_summaries\".\"exec_duration_ms\" end)")
                .doesNotContainIgnoringCase("filter (where");
    }

    @Test
    @DisplayName("the JSONExtract numeric metric accessor override renders a bound-key ClickHouse JSON path")
    void jsonExtractNumericMetricAccessorOverride() {
        ClickHouseEvalSummaryRepository repository =
                new ClickHouseEvalSummaryRepository(dsl, mock(EvalSummaryRecordMapper.class), mock(WhereBuilder.class));

        Field<BigDecimal> field = repository.buildNumericMetricAccessor(new MetricPath("Exact Match", "score"));

        assertThat(dsl.renderInlined(field))
                .isEqualTo("JSONExtract(\"analytics\".\"test_case_eval_summaries\".\"metric_values\", "
                        + "'Exact Match', 'score', 'Nullable(Float64)')");
    }

    @Test
    @DisplayName("the JSONExtract text metric accessor override renders a bound-key ClickHouse JSON path")
    void jsonExtractTextMetricAccessorOverride() {
        ClickHouseEvalSummaryRepository repository =
                new ClickHouseEvalSummaryRepository(dsl, mock(EvalSummaryRecordMapper.class), mock(WhereBuilder.class));

        Field<String> field = repository.buildTextMetricAccessor(new MetricPath("Exact Match", "score"));

        assertThat(dsl.renderInlined(field))
                .isEqualTo("JSONExtract(\"analytics\".\"test_case_eval_summaries\".\"metric_values\", "
                        + "'Exact Match', 'score', 'Nullable(String)')");
    }
}
