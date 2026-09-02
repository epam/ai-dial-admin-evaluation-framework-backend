package com.epam.aidial.evaluation.query.service.translate.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.DialectAwareJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.translate.ExprTranslator;
import com.epam.aidial.evaluation.query.service.translate.JsonbFieldResolver;
import com.epam.aidial.evaluation.query.service.translate.StructuredQueryBuilder;
import com.epam.aidial.evaluation.query.service.translate.ValueExprToObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Render-pinning test for {@link BuiltInQueryFunctions#percentileContFunction()}/{@code
 * percentileDiscFunction()}/{@code widthBucketFunction()} on {@link SQLDialect#CLICKHOUSE}.
 *
 * <p><b>History:</b> the initial P2 pass left {@code percentile_cont}/{@code percentile_disc} using
 * jOOQ's built-in {@code DSL.percentileCont}/{@code percentileDisc} unchanged for every family,
 * because jOOQ renders <em>some</em> valid ClickHouse SQL for them without a dialect-switch. That was
 * wrong for {@code percentile_cont}: jOOQ's ClickHouse rendering is CH's {@code quantile} aggregate,
 * which is an <em>approximate</em>, sampling-based estimate — not the exact linear interpolation
 * Postgres' {@code percentile_cont} computes. P10/P90/P99 metric-score statistics would silently
 * become nondeterministic and diverge semantically from Postgres. {@code
 * BuiltInQueryFunctions#percentile(FnExpr, FunctionContext, boolean)} now dialect-switches via {@link
 * com.epam.aidial.evaluation.data.db.repository.sql.DialectAwareSql} to the exact ClickHouse
 * equivalents: {@code quantileExactInclusive(fraction)(column)} for {@code percentile_cont}, {@code
 * quantileExactLow(fraction)(column)} for {@code percentile_disc} (jOOQ's default CLICKHOUSE
 * rendering for {@code percentile_disc} was already exact and coincides with this — the switch keeps
 * it, just via the explicit template instead of jOOQ's built-in).
 *
 * <p>{@code width_bucket} was likewise left untouched in P2 on the strength of its <em>rendering</em>
 * alone: jOOQ emits {@code width_bucket(...)}, which ClickHouse documents as an alias of
 * {@code widthBucket(...)}. Executing it against a live ClickHouse in P4 proved that insufficient —
 * ClickHouse requires the bucket <em>count</em> to be an unsigned integer and rejects jOOQ's
 * {@code cast(… as integer)} ({@code Int32}) with {@code ILLEGAL_TYPE_OF_ARGUMENT}. It now
 * dialect-switches to {@code widthBucket(…, toUInt32(count))}, pinned below.
 *
 * <p>Tests exercise the real {@link BuiltInQueryFunctions} beans end-to-end (through {@link
 * ExprTranslator}), not hand-written jOOQ calls, so the pinning reflects what production code
 * actually renders.
 */
class PercentileWidthBucketClickHouseRenderTest {

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(new DialectAwareJsonPathAccessor());
    private final BuiltInQueryFunctions builtIns = new BuiltInQueryFunctions(valueExprToObjectMapper);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider = mock(ObjectProvider.class);

    private final ExprTranslator exprTranslator = new ExprTranslator(
            valueExprToObjectMapper,
            jsonbFieldResolver,
            new QueryFunctionRegistry(List.of(
                    builtIns.percentileContFunction(),
                    builtIns.percentileDiscFunction(),
                    builtIns.widthBucketFunction())),
            queryBuilderProvider);

    private final Map<String, QueryFieldBinding> bindings = Map.of(
            "value",
            new QueryFieldBinding("value", DSL.field(DSL.name("value"), SQLDataType.NUMERIC), QueryFieldType.DECIMAL));

    private static ValueExpr decimal(String value) {
        return new ValueExpr(ValueType.DECIMAL, value);
    }

    private String render(SQLDialect dialect, FnExpr fn) {
        final DSLContext dsl = DSL.using(dialect);
        return dsl.renderInlined(exprTranslator.toField(fn, bindings)).toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("percentile_cont on ClickHouse renders the EXACT quantileExactInclusive, not approximate quantile")
    void percentileContOnClickHouseRendersExactQuantile() {
        final FnExpr fn = new FnExpr("percentile_cont", false, List.of(decimal("0.5"), new FieldExpr("value")));
        assertThat(render(SQLDialect.CLICKHOUSE, fn))
                .isEqualTo("quantileexactinclusive(0.5)(\"value\")")
                .doesNotContain("quantile(0.5)");
    }

    @Test
    @DisplayName("percentile_cont on the default family keeps today's Postgres percentileCont rendering")
    void percentileContOnDefaultFamilyUnchanged() {
        final FnExpr fn = new FnExpr("percentile_cont", false, List.of(decimal("0.5"), new FieldExpr("value")));
        assertThat(render(SQLDialect.DEFAULT, fn)).isEqualTo("percentile_cont(0.5) within group (order by \"value\")");
    }

    @Test
    @DisplayName("percentile_disc on ClickHouse renders the exact quantileExactLow")
    void percentileDiscOnClickHouseRendersQuantileExactLow() {
        final FnExpr fn = new FnExpr("percentile_disc", false, List.of(decimal("0.5"), new FieldExpr("value")));
        assertThat(render(SQLDialect.CLICKHOUSE, fn)).isEqualTo("quantileexactlow(0.5)(\"value\")");
    }

    @Test
    @DisplayName("percentile_disc on the default family keeps today's Postgres percentileDisc rendering")
    void percentileDiscOnDefaultFamilyUnchanged() {
        final FnExpr fn = new FnExpr("percentile_disc", false, List.of(decimal("0.5"), new FieldExpr("value")));
        assertThat(render(SQLDialect.DEFAULT, fn)).isEqualTo("percentile_disc(0.5) within group (order by \"value\")");
    }

    @Test
    @DisplayName("width_bucket on ClickHouse casts the bucket count to UInt32, which ClickHouse requires")
    void widthBucketOnClickHouseCastsCountToUnsigned() {
        final FnExpr fn = new FnExpr(
                "width_bucket", false, List.of(new FieldExpr("value"), decimal("0"), decimal("100"), decimal("10")));
        assertThat(render(SQLDialect.CLICKHOUSE, fn))
                .isEqualTo("widthbucket(\"value\", 0, 100, touint32(cast(10 as integer)))");
    }

    @Test
    @DisplayName("width_bucket on the default family keeps today's Postgres width_bucket rendering")
    void widthBucketOnDefaultFamilyUnchanged() {
        final FnExpr fn = new FnExpr(
                "width_bucket", false, List.of(new FieldExpr("value"), decimal("0"), decimal("100"), decimal("10")));
        assertThat(render(SQLDialect.DEFAULT, fn)).isEqualTo("width_bucket(\"value\", 0, 100, cast(10 as integer))");
    }
}
