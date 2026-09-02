package com.epam.aidial.evaluation.data.db.repository.sql.json;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.TEST_CASE_EVAL_SUMMARIES;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.jooq.analytics.Tables;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Render-pinning test for {@link DialectAwareJsonPathAccessor}'s ClickHouse branches, and a guard
 * that every other family — including {@link SQLDialect#DEFAULT} — still renders today's Postgres
 * expression unchanged (the existing consumer tests, e.g. {@code WhereBuilderTest}, pin that
 * Postgres rendering explicitly; this class only adds the ClickHouse coverage plus a DEFAULT-family
 * safety check).
 */
class DialectAwareJsonPathAccessorClickHouseRenderTest {

    /** The registered twin column from each generated model — both must trigger the map substitution. */
    private static final Field<JSONB> PG_MODEL_METRIC_VALUES = Tables.TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES;

    private static final Field<JSONB> CLICKHOUSE_MODEL_METRIC_VALUES = TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES;

    private final DialectAwareJsonPathAccessor accessor = new DialectAwareJsonPathAccessor();
    private final Field<JSONB> column = DSL.field(DSL.name("data"), SQLDataType.JSONB);
    private final Field<String> key = DSL.val("expected");
    private final Field<String> key2 = DSL.val("nested");

    private String render(SQLDialect dialect, Field<?> field) {
        final DSLContext dsl = DSL.using(dialect);
        return dsl.renderInlined(field);
    }

    @Test
    @DisplayName("jsonbAt on ClickHouse renders JSONExtractRaw(column, key)")
    void jsonbAtOnClickHouse() {
        assertThat(render(SQLDialect.CLICKHOUSE, accessor.jsonbAt(column, key)))
                .isEqualTo("JSONExtractRaw(\"data\", 'expected')");
    }

    @Test
    @DisplayName("jsonbAt on the default family renders unchanged Postgres jsonb -> operator SQL")
    void jsonbAtOnDefaultFamilyUnchanged() {
        assertThat(render(SQLDialect.DEFAULT, accessor.jsonbAt(column, key)))
                .isEqualTo(render(SQLDialect.DEFAULT, DSL.jsonbGetAttribute(column, key)));
    }

    @Test
    @DisplayName("jsonbAtAsText on ClickHouse renders JSONExtract(column, key, 'Nullable(String)')")
    void jsonbAtAsTextOnClickHouse() {
        assertThat(render(SQLDialect.CLICKHOUSE, accessor.jsonbAtAsText(column, key)))
                .isEqualTo("JSONExtract(\"data\", 'expected', 'Nullable(String)')");
    }

    @Test
    @DisplayName("jsonbAtAsText on the default family renders unchanged Postgres jsonb ->> operator SQL")
    void jsonbAtAsTextOnDefaultFamilyUnchanged() {
        assertThat(render(SQLDialect.DEFAULT, accessor.jsonbAtAsText(column, key)))
                .isEqualTo(render(SQLDialect.DEFAULT, DSL.jsonbGetAttributeAsText(column, key)));
    }

    @Test
    @DisplayName("jsonbAtAsNumeric on ClickHouse renders JSONExtract(column, key1, key2, 'Nullable(Float64)') "
            + "for a column without an acceleration twin")
    void jsonbAtAsNumericOnClickHouse() {
        assertThat(render(SQLDialect.CLICKHOUSE, accessor.jsonbAtAsNumeric(column, key, key2)))
                .isEqualTo("JSONExtract(\"data\", 'expected', 'nested', 'Nullable(Float64)')");
    }

    @Test
    @DisplayName("jsonbAtAsNumeric on ClickHouse reads the metric_values_map twin for eval-summaries "
            + "metric_values, from either generated model")
    void jsonbAtAsNumericOnClickHouseUsesMapTwin() {
        final String expected = "\"test_case_eval_summaries\".\"metric_values_map\"['expected']['nested']";
        assertThat(render(SQLDialect.CLICKHOUSE, accessor.jsonbAtAsNumeric(PG_MODEL_METRIC_VALUES, key, key2)))
                .isEqualTo(expected);
        assertThat(render(SQLDialect.CLICKHOUSE, accessor.jsonbAtAsNumeric(CLICKHOUSE_MODEL_METRIC_VALUES, key, key2)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("jsonbAtAsNumeric on the default family ignores the twin registry and renders Postgres SQL")
    void jsonbAtAsNumericOnDefaultFamilyIgnoresTwin() {
        assertThat(render(SQLDialect.DEFAULT, accessor.jsonbAtAsNumeric(PG_MODEL_METRIC_VALUES, key, key2)))
                .isEqualTo(render(
                        SQLDialect.DEFAULT,
                        DSL.jsonbGetAttributeAsText(DSL.jsonbGetAttribute(PG_MODEL_METRIC_VALUES, key), key2)
                                .cast(SQLDataType.NUMERIC)));
    }

    @Test
    @DisplayName("jsonbAtAsNumeric on the default family renders unchanged Postgres double-arrow + cast SQL")
    void jsonbAtAsNumericOnDefaultFamilyUnchanged() {
        assertThat(render(SQLDialect.DEFAULT, accessor.jsonbAtAsNumeric(column, key, key2)))
                .isEqualTo(render(
                        SQLDialect.DEFAULT,
                        DSL.jsonbGetAttributeAsText(DSL.jsonbGetAttribute(column, key), key2)
                                .cast(SQLDataType.NUMERIC)));
    }
}
