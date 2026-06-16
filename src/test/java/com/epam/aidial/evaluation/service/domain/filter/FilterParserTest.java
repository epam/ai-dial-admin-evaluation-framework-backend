package com.epam.aidial.evaluation.service.domain.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterParserTest {

    private final FilterParser parser = new FilterParser();

    @Test
    void shouldReturnEmptyListWhenNull() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenEmpty() {
        assertThat(parser.parse(List.of())).isEmpty();
    }

    @Test
    void shouldParseAndDecodeFilter() {
        List<FilterCondition> conditions = parser.parse(List.of("name:eq:hello%3Aworld"));

        assertThat(conditions).hasSize(1);
        FilterCondition condition = conditions.get(0);
        assertThat(condition.getField()).isEqualTo("name");
        assertThat(condition.getOperator()).isEqualTo(FilterOperator.EQ);
        assertThat(condition.getRawValue()).isEqualTo("hello:world");
        assertThat(condition.getParsedValue()).isNull();
    }

    @Test
    void shouldParseValueWithColon() {
        FilterCondition condition = parser.parse(List.of("name:eq:hello:world")).get(0);

        assertThat(condition.getRawValue()).isEqualTo("hello:world");
    }

    @Test
    void shouldParseOperatorCaseInsensitive() {
        FilterCondition condition = parser.parse(List.of("name:Co:abc")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.CO);
    }

    @Test
    void shouldRejectLegacyContainsOperator() {
        assertThatThrownBy(() -> parser.parse(List.of("name:contains:abc")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("unsupported operator 'contains'");
    }

    @Test
    void shouldRejectBlankField() {
        assertThatThrownBy(() -> parser.parse(List.of("  :eq:value")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("field must not be blank");
    }

    @Test
    void shouldRejectBlankOperator() {
        assertThatThrownBy(() -> parser.parse(List.of("name: :value")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("operator must not be blank");
    }

    @Test
    void shouldRejectBlankValueAfterDecoding() {
        assertThatThrownBy(() -> parser.parse(List.of("name:eq:%20")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("value must not be blank");
    }

    @Test
    void shouldRejectInvalidEncoding() {
        assertThatThrownBy(() -> parser.parse(List.of("name:eq:%G0")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("invalid URL encoding");
    }

    @Test
    void shouldRejectInvalidFormat() {
        assertThatThrownBy(() -> parser.parse(List.of("name:eq")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("expected <field>:<operator>:<value>");
    }

    @Test
    void shouldParseInOperatorWithTwoValues() {
        FilterCondition condition =
                parser.parse(List.of("testCaseName:in:alpha,beta")).get(0);

        assertThat(condition.getField()).isEqualTo("testCaseName");
        assertThat(condition.getOperator()).isEqualTo(FilterOperator.IN);
        assertThat(condition.getRawValue()).isEqualTo("alpha,beta");
        assertThat(condition.getParsedValue()).isEqualTo(List.of("alpha", "beta"));
    }

    @Test
    void shouldParseInOperatorWithSingleValue() {
        FilterCondition condition =
                parser.parse(List.of("testCaseName:in:only")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.IN);
        assertThat(condition.getParsedValue()).isEqualTo(List.of("only"));
    }

    @Test
    void shouldParseInOperatorCaseInsensitive() {
        FilterCondition condition = parser.parse(List.of("testCaseName:IN:a,b")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.IN);
    }

    @Test
    void shouldDecodePercentEncodedCommaAsLiteralInInElement() {
        FilterCondition condition =
                parser.parse(List.of("testCaseName:in:hello%2Cworld,second")).get(0);

        // %2C is decoded to a comma, which is a literal part of the first value
        // Split happens on raw before decode, so "hello%2Cworld" and "second" are two elements
        assertThat(condition.getParsedValue()).isEqualTo(List.of("hello,world", "second"));
    }

    @Test
    void shouldRejectInWithBlankElement() {
        assertThatThrownBy(() -> parser.parse(List.of("testCaseName:in:alpha,,beta")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("IN value elements must not be blank");
    }

    @Test
    void shouldRejectInWithAllBlankElements() {
        assertThatThrownBy(() -> parser.parse(List.of("testCaseName:in:,")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("IN value elements must not be blank");
    }

    @Test
    void shouldParseGeOperatorToCanonicalGe() {
        FilterCondition condition =
                parser.parse(List.of("createdAt:ge:1700000000000")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.GE);
        assertThat(condition.getRawValue()).isEqualTo("1700000000000");
    }

    @Test
    void shouldParseLeOperatorToCanonicalLe() {
        FilterCondition condition =
                parser.parse(List.of("createdAt:le:1800000000000")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.LE);
        assertThat(condition.getRawValue()).isEqualTo("1800000000000");
    }

    @Test
    void shouldParseGeOperatorCaseInsensitive() {
        FilterCondition uppercase = parser.parse(List.of("createdAt:GE:1")).get(0);
        FilterCondition titleCase = parser.parse(List.of("createdAt:Ge:1")).get(0);

        assertThat(uppercase.getOperator()).isEqualTo(FilterOperator.GE);
        assertThat(titleCase.getOperator()).isEqualTo(FilterOperator.GE);
    }

    @Test
    void shouldParseDeprecatedGteAliasAsCanonicalGe() {
        FilterCondition condition =
                parser.parse(List.of("createdAt:gte:1700000000000")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.GE);
        assertThat(condition.getRawValue()).isEqualTo("1700000000000");
    }

    @Test
    void shouldParseDeprecatedLteAliasAsCanonicalLe() {
        FilterCondition condition =
                parser.parse(List.of("createdAt:lte:1800000000000")).get(0);

        assertThat(condition.getOperator()).isEqualTo(FilterOperator.LE);
        assertThat(condition.getRawValue()).isEqualTo("1800000000000");
    }

    @Test
    void shouldParseDeprecatedAliasesCaseInsensitive() {
        FilterCondition gteUpper = parser.parse(List.of("createdAt:GTE:1")).get(0);
        FilterCondition lteMixed = parser.parse(List.of("createdAt:Lte:1")).get(0);

        assertThat(gteUpper.getOperator()).isEqualTo(FilterOperator.GE);
        assertThat(lteMixed.getOperator()).isEqualTo(FilterOperator.LE);
    }

    @Test
    void shouldRejectUnknownOperator() {
        assertThatThrownBy(() -> parser.parse(List.of("name:startsWith:abc")))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("unsupported operator")
                .satisfies(ex -> {
                    FilterValidationException exception = (FilterValidationException) ex;
                    assertThat(exception.getDetails())
                            .containsEntry("filter", "name:startsWith:abc")
                            .containsEntry("field", "name")
                            .containsEntry("operator", "startsWith");
                });
    }
}
