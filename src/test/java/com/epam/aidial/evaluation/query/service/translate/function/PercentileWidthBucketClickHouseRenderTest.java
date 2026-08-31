package com.epam.aidial.evaluation.query.service.translate.function;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Render probe (P2 task 5) for whether jOOQ's built-in {@code DSL.percentileCont}/{@code
 * percentileDisc}/{@code widthBucket} — used unchanged by {@link BuiltInQueryFunctions} for both
 * datasources — render valid SQL on {@link SQLDialect#CLICKHOUSE} without a dialect-switch.
 *
 * <p>Outcome: they do, so {@link BuiltInQueryFunctions#percentileContFunction()}/{@code
 * percentileDiscFunction()}/{@code widthBucketFunction()} are left untouched; this class is the
 * pinning test for that decision.
 *
 * <p>{@code percentile_cont} renders as CH's {@code quantile} aggregate, which is an
 * <em>approximate</em> quantile (reservoir sampling), not Postgres' exact continuous interpolation —
 * a documented semantic divergence for P4's functional suite to assess if exactness matters for
 * consumers. {@code percentile_disc} renders as the exact {@code quantileExactLow}. {@code
 * width_bucket} renders unchanged as CH's documented {@code width_bucket} alias for {@code
 * widthBucket}.
 */
class PercentileWidthBucketClickHouseRenderTest {

    private final DSLContext dsl = DSL.using(SQLDialect.CLICKHOUSE);

    @Test
    @DisplayName("percentile_cont renders as ClickHouse's approximate quantile(fraction)(column)")
    void percentileContRendersAsQuantile() {
        final Field<BigDecimal> fraction = DSL.val(new BigDecimal("0.5"));
        final Field<BigDecimal> orderField = DSL.field(DSL.name("value"), SQLDataType.NUMERIC);
        final Field<?> cont = DSL.percentileCont(fraction).withinGroupOrderBy(orderField);
        assertThat(dsl.renderInlined(cont)).isEqualTo("quantile(0.5)(\"value\")");
    }

    @Test
    @DisplayName("percentile_disc renders as ClickHouse's exact quantileExactLow(fraction)(column)")
    void percentileDiscRendersAsQuantileExactLow() {
        final Field<BigDecimal> fraction = DSL.val(new BigDecimal("0.5"));
        final Field<BigDecimal> orderField = DSL.field(DSL.name("value"), SQLDataType.NUMERIC);
        final Field<?> disc = DSL.percentileDisc(fraction).withinGroupOrderBy(orderField);
        assertThat(dsl.renderInlined(disc)).isEqualTo("quantileExactLow(0.5)(\"value\")");
    }

    @Test
    @DisplayName("width_bucket renders unchanged as ClickHouse's width_bucket alias")
    void widthBucketRendersUnchanged() {
        final Field<BigDecimal> operand = DSL.field(DSL.name("x"), SQLDataType.NUMERIC);
        final Field<BigDecimal> low = DSL.val(new BigDecimal("0"));
        final Field<BigDecimal> high = DSL.val(new BigDecimal("100"));
        final Field<Integer> count = DSL.val(10);
        final Field<?> widthBucket = DSL.widthBucket(operand, low, high, count);
        assertThat(dsl.renderInlined(widthBucket)).isEqualTo("width_bucket(\"x\", 0, 100, 10)");
    }
}
