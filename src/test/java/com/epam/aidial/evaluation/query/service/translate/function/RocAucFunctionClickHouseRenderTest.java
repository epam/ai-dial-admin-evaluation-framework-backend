package com.epam.aidial.evaluation.query.service.translate.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.DialectAwareJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
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
 * Render-pinning test for {@link RocAucFunction}'s dialect-switch: on {@link SQLDialect#CLICKHOUSE}
 * it renders {@code arrayAUC(groupArray(probability), groupArray(label))} — note the swapped
 * argument order relative to the Postgres {@code roc_auc_score(array_agg(label),
 * array_agg(probability))} form, which every other family (including {@link SQLDialect#DEFAULT})
 * must keep rendering byte-identical.
 */
class RocAucFunctionClickHouseRenderTest {

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(new DialectAwareJsonPathAccessor());

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider = mock(ObjectProvider.class);

    private final ExprTranslator exprTranslator = new ExprTranslator(
            valueExprToObjectMapper,
            jsonbFieldResolver,
            new QueryFunctionRegistry(List.of(new RocAucFunction())),
            queryBuilderProvider);

    private final Map<String, QueryFieldBinding> bindings = Map.of(
            "label",
                    new QueryFieldBinding(
                            "label", DSL.field(DSL.name("label"), SQLDataType.VARCHAR), QueryFieldType.STRING),
            "probability",
                    new QueryFieldBinding(
                            "probability",
                            DSL.field(DSL.name("probability"), SQLDataType.NUMERIC),
                            QueryFieldType.DECIMAL));

    private final FnExpr rocAuc =
            new FnExpr("roc_auc", false, List.of(new FieldExpr("label"), new FieldExpr("probability")));

    private String render(SQLDialect dialect) {
        final DSLContext dsl = DSL.using(dialect);
        return dsl.renderInlined(exprTranslator.toField(rocAuc, bindings)).toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("roc_auc on ClickHouse renders arrayAUC(groupArray(probability), groupArray(label))")
    void renderOnClickHouse() {
        assertThat(render(SQLDialect.CLICKHOUSE))
                .isEqualTo("arrayauc(grouparray(cast(\"probability\" as nullable(double))), "
                        + "grouparray(cast(\"label\" as nullable(double))))");
    }

    @Test
    @DisplayName("roc_auc on Postgres keeps today's roc_auc_score(array_agg(label), array_agg(probability))")
    void renderOnPostgresUnchanged() {
        assertThat(render(SQLDialect.POSTGRES))
                .isEqualTo("roc_auc_score(array_agg(cast(\"label\" as double precision)), "
                        + "array_agg(cast(\"probability\" as double precision)))");
    }

    @Test
    @DisplayName("roc_auc on the default family takes the Postgres branch, never the ClickHouse one")
    void renderOnDefaultFamilyTakesPostgresBranch() {
        assertThat(render(SQLDialect.DEFAULT)).contains("roc_auc_score").doesNotContain("arrayauc");
    }
}
