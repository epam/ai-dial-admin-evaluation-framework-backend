package com.epam.aidial.evaluation.query.service.translate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.CaseExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.model.WhenClause;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for {@link ExprTranslator#toField}'s rejection of expression kinds that are only valid
 *  outside internal entity translation. */
class ExprTranslatorTest {

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(new PostgresJsonPathAccessor());

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider = mock(ObjectProvider.class);

    private final ExprTranslator exprTranslator = new ExprTranslator(
            valueExprToObjectMapper,
            jsonbFieldResolver,
            QueryFunctionTestSupport.registry(valueExprToObjectMapper),
            queryBuilderProvider);

    @Test
    @DisplayName("rejects a case expression for an internal entity")
    void rejectsCaseExpression() {
        CaseExpr caseExpr = new CaseExpr(
                List.of(new WhenClause(null, new ValueExpr(ValueType.STRING, "A"))),
                new ValueExpr(ValueType.STRING, "other"));

        assertThatThrownBy(() -> exprTranslator.toField(caseExpr, Map.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("case expressions are not supported for entity queries");
    }
}
