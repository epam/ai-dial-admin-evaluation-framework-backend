package com.epam.aidial.evaluation.data.db.repository.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.repository.sql.json.DialectAwareJsonPathAccessor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WhereBuilderTest {

    private static final DSLContext DSL_CTX = DSL.using(SQLDialect.POSTGRES);
    private final WhereBuilder builder = new WhereBuilder(new DialectAwareJsonPathAccessor());

    @Test
    void shouldReturnTrueConditionWhenNoConditions() {
        Condition condition = builder.build(List.of(), FilterWhitelists.TEST_SUITES);

        String rendered = DSL_CTX.renderInlined(condition);
        assertThat(rendered)
                .satisfiesAnyOf(
                        r -> assertThat(r).isEqualTo("1 = 1"),
                        r -> assertThat(r).isEqualTo("true"));
    }

    @Test
    void shouldBuildConditionForStringEq() {
        FilterCondition condition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.EQ)
                .rawValue("Alpha")
                .build();

        Condition result = builder.build(List.of(condition), FilterWhitelists.TEST_SUITES);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(");
        assertThat(sql).containsIgnoringCase("alpha");
        assertThat(condition.getParsedValue()).isEqualTo("Alpha");
    }

    @Test
    void shouldBuildConditionForStringNe() {
        FilterCondition condition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.NE)
                .rawValue("Alpha")
                .build();

        Condition result = builder.build(List.of(condition), FilterWhitelists.TEST_SUITES);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(");
        assertThat(sql).contains("<>");
    }

    @Test
    void shouldBuildConditionForCo() {
        FilterCondition condition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.CO)
                .rawValue("suite")
                .build();

        Condition result = builder.build(List.of(condition), FilterWhitelists.TEST_SUITES);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("ilike");
        assertThat(sql).contains("%suite%");
    }

    @Test
    void shouldBuildConditionWithMultipleConditions() {
        FilterCondition nameCondition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.EQ)
                .rawValue("Alpha")
                .build();
        FilterCondition createdAtCondition = FilterCondition.builder()
                .field("createdAt")
                .operator(FilterOperator.GE)
                .rawValue("100")
                .build();

        Condition result = builder.build(List.of(nameCondition, createdAtCondition), FilterWhitelists.TEST_SUITES);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(");
        assertThat(sql).containsIgnoringCase("alpha");
        assertThat(sql).contains(">=");
        assertThat(sql).contains("100");
        assertThat(sql).containsIgnoringCase("and");
        assertThat(nameCondition.getParsedValue()).isEqualTo("Alpha");
        assertThat(createdAtCondition.getParsedValue()).isEqualTo(100L);
    }

    @Test
    void shouldParseLongValue() {
        FilterCondition condition = FilterCondition.builder()
                .field("createdAt")
                .operator(FilterOperator.GE)
                .rawValue("1000")
                .build();

        builder.build(List.of(condition), FilterWhitelists.TEST_SUITES);

        assertThat(condition.getParsedValue()).isEqualTo(1000L);
    }

    @Test
    void shouldParseBooleanCaseInsensitive() {
        FilterCondition condition = FilterCondition.builder()
                .field("valid")
                .operator(FilterOperator.EQ)
                .rawValue("TrUe")
                .build();

        builder.build(List.of(condition), FilterWhitelists.TEST_CASES);

        assertThat(condition.getParsedValue()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void shouldParseUuidValue() {
        FilterSpec spec = FilterSpec.of(
                Map.of("id", FilterFieldDefinition.of("id", FilterFieldType.UUID, EnumSet.of(FilterOperator.EQ))));
        FilterCondition condition = FilterCondition.builder()
                .field("id")
                .operator(FilterOperator.EQ)
                .rawValue("11111111-1111-1111-1111-111111111111")
                .build();

        Condition result = builder.build(List.of(condition), spec);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).contains("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void shouldRejectUnknownField() {
        FilterCondition condition = FilterCondition.builder()
                .field("unknown")
                .operator(FilterOperator.EQ)
                .rawValue("value")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_SUITES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("unknown field")
                .satisfies(ex -> {
                    InvalidFilterException exception = (InvalidFilterException) ex;
                    assertThat(exception.getDetails())
                            .containsEntry("field", "unknown")
                            .containsKey("reason");
                });
    }

    @Test
    void shouldRejectOperatorNotAllowed() {
        FilterCondition condition = FilterCondition.builder()
                .field("createdBy")
                .operator(FilterOperator.CO)
                .rawValue("owner")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_SUITES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void shouldRejectCoOnNonStringEvenIfAllowed() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "createdAt",
                FilterFieldDefinition.of("created_at_ms", FilterFieldType.LONG, EnumSet.of(FilterOperator.CO))));
        FilterCondition condition = FilterCondition.builder()
                .field("createdAt")
                .operator(FilterOperator.CO)
                .rawValue("123")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), spec))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("only allowed for STRING or JSONB_STRING fields");
    }

    @Test
    void shouldRejectInvalidBooleanValue() {
        FilterCondition condition = FilterCondition.builder()
                .field("valid")
                .operator(FilterOperator.EQ)
                .rawValue("yes")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_CASES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("invalid boolean value");
    }

    @Test
    void shouldRejectInvalidLongValue() {
        FilterCondition condition = FilterCondition.builder()
                .field("createdAt")
                .operator(FilterOperator.GE)
                .rawValue("abc")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_SUITES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("invalid long value");
    }

    @Test
    void shouldRejectNullCondition() {
        List<FilterCondition> listWithNull = new ArrayList<>();
        listWithNull.add(null);
        assertThatThrownBy(() -> builder.build(listWithNull, FilterWhitelists.TEST_SUITES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Filter condition must not be null");
    }

    @Test
    void shouldRejectNullOperator() {
        FilterCondition condition =
                FilterCondition.builder().field("name").rawValue("Alpha").build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_SUITES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("operator must not be null");
    }

    @Test
    void shouldRejectNullRawValue() {
        FilterCondition condition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.EQ)
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_SUITES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("Should build JSONB_NUMERIC two-level path predicate")
    void shouldBuildJsonbNumericTwoLevelPath() {
        FilterCondition condition = FilterCondition.builder()
                .field("metricValues.Accuracy.score")
                .operator(FilterOperator.GE)
                .rawValue("0.8")
                .build();

        Condition result = builder.build(List.of(condition), FilterWhitelists.EVAL_SUMMARIES);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("numeric");
        assertThat(sql).contains("Accuracy");
        assertThat(sql).contains("score");
        assertThat(sql).contains(">=");
        assertThat(sql).contains("0.8");
        assertThat(condition.getParsedValue()).isInstanceOf(BigDecimal.class);
        assertThat(condition.getParsedValue()).isEqualTo(new BigDecimal("0.8"));
    }

    @Test
    @DisplayName("Should reject JSONB_NUMERIC with single-level path")
    void shouldRejectJsonbNumericSingleLevelPath() {
        FilterCondition condition = FilterCondition.builder()
                .field("metricValues.score")
                .operator(FilterOperator.GE)
                .rawValue("0.8")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.EVAL_SUMMARIES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("JSONB_NUMERIC requires two-level path");
    }

    @Test
    @DisplayName("Should reject JSONB_NUMERIC with three-level path")
    void shouldRejectJsonbNumericThreeLevelPath() {
        FilterCondition condition = FilterCondition.builder()
                .field("metricValues.A.B.C")
                .operator(FilterOperator.GE)
                .rawValue("0.8")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.EVAL_SUMMARIES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("nested JSONB paths deeper than two levels");
    }

    @Test
    @DisplayName("Should parse JSONB_NUMERIC value as BigDecimal")
    void shouldParseJsonbNumericValueAsBigDecimal() {
        FilterCondition condition = FilterCondition.builder()
                .field("metricValues.Accuracy.score")
                .operator(FilterOperator.EQ)
                .rawValue("3.14159")
                .build();

        builder.build(List.of(condition), FilterWhitelists.EVAL_SUMMARIES);

        assertThat(condition.getParsedValue()).isInstanceOf(BigDecimal.class);
        assertThat(condition.getParsedValue()).isEqualTo(new BigDecimal("3.14159"));
    }

    @Test
    @DisplayName("Should reject invalid numeric value for JSONB_NUMERIC")
    void shouldRejectInvalidNumericValue() {
        FilterCondition condition = FilterCondition.builder()
                .field("metricValues.A.B")
                .operator(FilterOperator.EQ)
                .rawValue("abc")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.EVAL_SUMMARIES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("invalid numeric value");
    }

    @Test
    @DisplayName("Should reject CO operator on JSONB_NUMERIC field")
    void shouldRejectCoOnJsonbNumeric() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "metricValues",
                FilterFieldDefinition.of(
                        "metric_values",
                        FilterFieldType.JSONB_NUMERIC,
                        EnumSet.of(FilterOperator.EQ, FilterOperator.CO))));
        FilterCondition condition = FilterCondition.builder()
                .field("metricValues.A.B")
                .operator(FilterOperator.CO)
                .rawValue("0.5")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), spec))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("only allowed for STRING or JSONB_STRING fields");
    }

    @Test
    @DisplayName("Should build IN predicate for STRING field")
    void shouldBuildInPredicateForStringField() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseName")
                .operator(FilterOperator.IN)
                .rawValue("alpha,beta")
                .parsedValue(List.of("alpha", "beta"))
                .build();

        Condition result = builder.build(List.of(condition), FilterWhitelists.TEST_CASES);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("in");
        assertThat(sql).contains("alpha");
        assertThat(sql).contains("beta");
        assertThat(condition.getParsedValue()).isEqualTo(List.of("alpha", "beta"));
    }

    @Test
    @DisplayName("Should build IN predicate for UUID field and validate each element")
    void shouldBuildInPredicateForUuidFieldAndValidateElements() {
        String uuid1 = "11111111-1111-1111-1111-111111111111";
        String uuid2 = "22222222-2222-2222-2222-222222222222";
        FilterSpec spec = FilterSpec.of(
                Map.of("id", FilterFieldDefinition.of("id", FilterFieldType.UUID, EnumSet.of(FilterOperator.IN))));
        FilterCondition condition = FilterCondition.builder()
                .field("id")
                .operator(FilterOperator.IN)
                .rawValue(uuid1 + "," + uuid2)
                .parsedValue(List.of(uuid1, uuid2))
                .build();

        Condition result = builder.build(List.of(condition), spec);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("in");
        assertThat(sql).contains(uuid1);
        assertThat(sql).contains(uuid2);
    }

    @Test
    @DisplayName("Should reject IN on BOOLEAN field type")
    void shouldRejectInOnBooleanField() {
        FilterCondition condition = FilterCondition.builder()
                .field("valid")
                .operator(FilterOperator.IN)
                .rawValue("true,false")
                .parsedValue(List.of("true", "false"))
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_CASES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("Should reject IN on LONG field type")
    void shouldRejectInOnLongField() {
        FilterCondition condition = FilterCondition.builder()
                .field("createdAt")
                .operator(FilterOperator.IN)
                .rawValue("100,200")
                .parsedValue(List.of("100", "200"))
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), FilterWhitelists.TEST_SUITES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("Should reject IN on UUID field when element is not a valid UUID")
    void shouldRejectInOnUuidFieldWithInvalidElement() {
        FilterSpec spec = FilterSpec.of(
                Map.of("id", FilterFieldDefinition.of("id", FilterFieldType.UUID, EnumSet.of(FilterOperator.IN))));
        FilterCondition condition = FilterCondition.builder()
                .field("id")
                .operator(FilterOperator.IN)
                .rawValue("not-a-uuid,22222222-2222-2222-2222-222222222222")
                .parsedValue(List.of("not-a-uuid", "22222222-2222-2222-2222-222222222222"))
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), spec))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("invalid UUID value");
    }

    @Test
    @DisplayName("EQ on non-STRING type (UUID) should use exact match, not lower()")
    void shouldBuildExactEqForUuidField() {
        FilterSpec spec = FilterSpec.of(
                Map.of("id", FilterFieldDefinition.of("id", FilterFieldType.UUID, EnumSet.of(FilterOperator.EQ))));
        FilterCondition condition = FilterCondition.builder()
                .field("id")
                .operator(FilterOperator.EQ)
                .rawValue("11111111-1111-1111-1111-111111111111")
                .build();

        Condition result = builder.build(List.of(condition), spec);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).doesNotContainIgnoringCase("lower(");
        assertThat(sql).contains("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void shouldRejectMissingAllowedFields() {
        FilterSpec spec = FilterSpec.builder().allowedFields(Map.of()).build();
        FilterCondition condition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.EQ)
                .rawValue("Alpha")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filterSpec must define allowed fields");
    }
}
