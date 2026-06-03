package com.epam.aidial.evaluation.data.db.repository.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WhereBuilder JSONB path filtering")
class WhereBuilderJsonbTest {

    private static final DSLContext DSL_CTX = DSL.using(SQLDialect.POSTGRES);

    private final WhereBuilder builder = new WhereBuilder(new PostgresJsonPathAccessor());

    private static final FilterSpec SPEC = FilterSpec.of(Map.of(
            "testCaseData",
                    FilterFieldDefinition.of(
                            "test_case_data",
                            FilterFieldType.JSONB_STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO)),
            "name",
                    FilterFieldDefinition.of(
                            "name",
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO))));

    @Test
    @DisplayName("Should generate JSONB EQ predicate with case-insensitive lower()")
    void shouldGenerateJsonbEqPredicate() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseData.prompt")
                .operator(FilterOperator.EQ)
                .rawValue("hello")
                .build();

        Condition result = builder.build(List.of(condition), SPEC);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(");
        assertThat(sql).contains("test_case_data");
        assertThat(sql).contains("prompt");
        assertThat(sql).containsIgnoringCase("lower('hello')");
    }

    @Test
    @DisplayName("Should generate JSONB NE predicate with case-insensitive lower()")
    void shouldGenerateJsonbNePredicate() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseData.category")
                .operator(FilterOperator.NE)
                .rawValue("test")
                .build();

        Condition result = builder.build(List.of(condition), SPEC);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(");
        assertThat(sql).contains("test_case_data");
        assertThat(sql).contains("category");
        assertThat(sql).contains("<>");
        assertThat(sql).containsIgnoringCase("lower('test')");
    }

    @Test
    @DisplayName("Should generate JSONB CO predicate with parameterized key")
    void shouldGenerateJsonbCoPredicate() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseData.prompt")
                .operator(FilterOperator.CO)
                .rawValue("hello")
                .build();

        Condition result = builder.build(List.of(condition), SPEC);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).contains("test_case_data");
        assertThat(sql).contains("prompt");
        assertThat(sql).containsIgnoringCase("ilike");
        assertThat(sql).contains("hello");
    }

    @Test
    @DisplayName("Should reject nested JSONB paths")
    void shouldRejectNestedJsonbPaths() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseData.meta.category")
                .operator(FilterOperator.EQ)
                .rawValue("test")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), SPEC))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("nested JSONB paths not supported");
    }

    @Test
    @DisplayName("Should reject empty JSONB key")
    void shouldRejectEmptyJsonbKey() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseData.")
                .operator(FilterOperator.EQ)
                .rawValue("test")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), SPEC))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("JSONB key must not be empty");
    }

    @Test
    @DisplayName("Should reject unknown prefix with dot notation")
    void shouldRejectUnknownPrefixWithDotNotation() {
        FilterCondition condition = FilterCondition.builder()
                .field("unknownField.key")
                .operator(FilterOperator.EQ)
                .rawValue("test")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), SPEC))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    @DisplayName("Should reject dot notation on non-JSONB field")
    void shouldRejectDotNotationOnNonJsonbField() {
        FilterCondition condition = FilterCondition.builder()
                .field("name.subfield")
                .operator(FilterOperator.EQ)
                .rawValue("test")
                .build();

        assertThatThrownBy(() -> builder.build(List.of(condition), SPEC))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("does not support JSONB path access");
    }

    @Test
    @DisplayName("Should still handle regular STRING fields with case-insensitive EQ alongside JSONB spec")
    void shouldHandleRegularFieldsCorrectly() {
        FilterCondition condition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.EQ)
                .rawValue("test-name")
                .build();

        Condition result = builder.build(List.of(condition), SPEC);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(name)");
        assertThat(sql).containsIgnoringCase("lower('test-name')");
    }

    @Test
    @DisplayName("Should combine JSONB and regular field conditions")
    void shouldCombineJsonbAndRegularFieldConditions() {
        FilterCondition nameCondition = FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.EQ)
                .rawValue("my-name")
                .build();
        FilterCondition jsonbCondition = FilterCondition.builder()
                .field("testCaseData.prompt")
                .operator(FilterOperator.CO)
                .rawValue("search")
                .build();

        Condition result = builder.build(List.of(nameCondition, jsonbCondition), SPEC);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(name)");
        assertThat(sql).containsIgnoringCase("lower('my-name')");
        assertThat(sql).contains("test_case_data");
        assertThat(sql).contains("prompt");
        assertThat(sql).containsIgnoringCase("ilike");
        assertThat(sql).contains("search");
        assertThat(sql).containsIgnoringCase("and");
    }

    @Test
    @DisplayName("Should work with ANALYTICS_RESULTS FilterWhitelist for JSONB with case-insensitive EQ")
    void shouldWorkWithAnalyticsResultsWhitelistForJsonb() {
        FilterCondition condition = FilterCondition.builder()
                .field("testCaseData.inputText")
                .operator(FilterOperator.EQ)
                .rawValue("sample")
                .build();

        Condition result = builder.build(List.of(condition), FilterWhitelists.ANALYTICS_RESULTS);

        String sql = DSL_CTX.renderInlined(result);
        assertThat(sql).containsIgnoringCase("lower(");
        assertThat(sql).contains("test_case_data");
        assertThat(sql).contains("inputText");
        assertThat(sql).containsIgnoringCase("lower('sample')");
    }
}
