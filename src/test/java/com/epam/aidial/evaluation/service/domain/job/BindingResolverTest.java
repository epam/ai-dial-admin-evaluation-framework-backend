package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.DashjoinJsonataEvaluationService;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBindingSourceDto;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("BindingResolver")
class BindingResolverTest {

    private BindingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BindingResolver(new ObjectMapper(), new DashjoinJsonataEvaluationService(new ObjectMapper()));
    }

    @Test
    @DisplayName("Should resolve TestCase binding from test case data")
    void shouldResolveTestCaseBinding() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("reference")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("expected_output")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of("expected_output", "the answer");
        Map<String, Object> extractedColumns = Map.of();

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, extractedColumns);

        assertThat(result).containsEntry("reference", "the answer");
    }

    @Test
    @DisplayName("Should resolve Response binding from extracted columns")
    void shouldResolveResponseBinding() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("actual")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("model_answer")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of();
        Map<String, Object> extractedColumns = Map.of("model_answer", "generated text");

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, extractedColumns);

        assertThat(result).containsEntry("actual", "generated text");
    }

    @Test
    @DisplayName("Should resolve Constant binding to literal value")
    void shouldResolveConstantBinding() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("threshold")
                .source(ConstantBindingSourceDto.builder().value(0.8).build())
                .build();

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), Map.of());

        assertThat(result).containsEntry("threshold", 0.8);
    }

    @Test
    @DisplayName("Should throw when TestCase binding references missing column")
    void shouldThrowForMissingTestCaseColumn() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("reference")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("nonexistent")
                        .build())
                .build();

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent")
                .hasMessageContaining("test case data");
    }

    @Test
    @DisplayName("Should throw when Response binding references missing column")
    void shouldThrowForMissingResponseColumn() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("actual")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("missing_col")
                        .build())
                .build();

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing_col")
                .hasMessageContaining("extracted columns");
    }

    @Test
    @DisplayName("TestCase binding jsonataExpression selects an array element (per-turn data column)")
    void testCaseBinding_jsonataExpression_selectsArrayElement() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("user_message")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("user_turns")
                        .jsonataExpression("$[1]")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of("user_turns", List.of("hi", "and then?"));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of());

        assertThat(result).containsEntry("user_message", "and then?");
    }

    @Test
    @DisplayName("TestCase binding without jsonataExpression returns the whole column value")
    void testCaseBinding_noExpression_returnsWholeValue() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("turns")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("user_turns")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of("user_turns", List.of("hi", "and then?"));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of());

        assertThat(result).containsEntry("turns", List.of("hi", "and then?"));
    }

    @Test
    @DisplayName("TestCase binding jsonataExpression selects a nested object path")
    void testCaseBinding_jsonataExpression_selectsObjectPath() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("topic")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("meta")
                        .jsonataExpression("labels.topic")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of("meta", Map.of("labels", Map.of("topic", "geography")));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of());

        assertThat(result).containsEntry("topic", "geography");
    }

    @Test
    @DisplayName("TestCase binding jsonataExpression matching nothing resolves to null")
    void testCaseBinding_jsonataExpression_noMatch_resolvesToNull() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("fifth")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("user_turns")
                        .jsonataExpression("$[5]")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of("user_turns", List.of("hi", "and then?"));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of());

        assertThat(result).containsKey("fifth");
        assertThat(result.get("fifth")).isNull();
    }

    @Test
    @DisplayName("Response binding without jsonataExpression returns the whole multi-step array")
    void responseBinding_noExpression_returnsWholeArray() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("answers")
                .source(ResponseBindingSourceDto.builder().columnName("answer").build())
                .build();

        Map<String, Object> extractedColumns = Map.of("answer", List.of("Paris", "Tokio"));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), extractedColumns);

        assertThat(result).containsEntry("answers", List.of("Paris", "Tokio"));
    }

    @Test
    @DisplayName("Response binding jsonataExpression selects an array element")
    void responseBinding_jsonataExpression_selectsArrayElement() {
        MetricParameterBindingDto first = MetricParameterBindingDto.builder()
                .property("first")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("answer")
                        .jsonataExpression("$[0]")
                        .build())
                .build();
        MetricParameterBindingDto last = MetricParameterBindingDto.builder()
                .property("last")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("answer")
                        .jsonataExpression("$[-1]")
                        .build())
                .build();

        Map<String, Object> extractedColumns = Map.of("answer", List.of("Paris", "Tokio"));

        Map<String, Object> result = resolver.resolveBindings(List.of(first, last), Map.of(), extractedColumns);

        assertThat(result).containsEntry("first", "Paris").containsEntry("last", "Tokio");
    }

    @Test
    @DisplayName("Response binding jsonataExpression selects a nested object path")
    void responseBinding_jsonataExpression_selectsObjectPath() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("total")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("meta")
                        .jsonataExpression("usage.total")
                        .build())
                .build();

        Map<String, Object> extractedColumns = Map.of("meta", Map.of("usage", Map.of("total", 42)));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), extractedColumns);

        assertThat(result).containsEntry("total", 42);
    }

    @Test
    @DisplayName("Response binding jsonataExpression matching nothing resolves to null")
    void responseBinding_jsonataExpression_noMatch_resolvesToNull() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("third")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("answer")
                        .jsonataExpression("$[2]")
                        .build())
                .build();

        Map<String, Object> extractedColumns = Map.of("answer", List.of("Paris", "Tokio"));

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), extractedColumns);

        assertThat(result).containsKey("third");
        assertThat(result.get("third")).isNull();
    }

    @Test
    @DisplayName("Response binding jsonataExpression that errors at runtime resolves to null (not a hard failure)")
    void responseBinding_jsonataExpression_runtimeError_resolvesToNull() {
        // "$ + 1" is valid JSONata syntax (passes config-time validation) but throws at evaluation
        // time when the resolved column value is a non-numeric string. Per the "matches nothing ->
        // null" contract, this must degrade to null rather than propagate and fail the whole metric.
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("score")
                .source(ResponseBindingSourceDto.builder()
                        .columnName("answer")
                        .jsonataExpression("$ + 1")
                        .build())
                .build();

        Map<String, Object> extractedColumns = Map.of("answer", "not-a-number");

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), extractedColumns);

        assertThat(result).containsKey("score");
        assertThat(result.get("score")).isNull();
    }

    @Test
    @DisplayName("TestCase binding jsonataExpression that errors at runtime resolves to null (not a hard failure)")
    void testCaseBinding_jsonataExpression_runtimeError_resolvesToNull() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("score")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("label")
                        .jsonataExpression("$ + 1")
                        .build())
                .build();

        Map<String, Object> testCaseData = Map.of("label", "not-a-number");

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of());

        assertThat(result).containsKey("score");
        assertThat(result.get("score")).isNull();
    }

    @Test
    @DisplayName("Should return null when column exists but has null value")
    void shouldReturnNullForPresentButNullColumn() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("reference")
                .source(TestCaseBindingSourceDto.builder()
                        .columnName("nullable_col")
                        .build())
                .build();

        Map<String, Object> testCaseData = new java.util.HashMap<>();
        testCaseData.put("nullable_col", null);

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of());

        assertThat(result).containsKey("reference");
        assertThat(result.get("reference")).isNull();
    }

    @Test
    @DisplayName("Should return empty map for empty bindings")
    void shouldReturnEmptyMapForEmptyBindings() {
        Map<String, Object> result = resolver.resolveBindings(Collections.emptyList(), Map.of(), Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty map for null bindings")
    void shouldReturnEmptyMapForNullBindings() {
        Map<String, Object> result = resolver.resolveBindings(null, Map.of(), Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should merge multiple bindings correctly")
    void shouldMergeMultipleBindings() {
        List<MetricParameterBindingDto> bindings = List.of(
                MetricParameterBindingDto.builder()
                        .property("reference")
                        .source(TestCaseBindingSourceDto.builder()
                                .columnName("expected")
                                .build())
                        .build(),
                MetricParameterBindingDto.builder()
                        .property("actual")
                        .source(ResponseBindingSourceDto.builder()
                                .columnName("answer")
                                .build())
                        .build(),
                MetricParameterBindingDto.builder()
                        .property("mode")
                        .source(ConstantBindingSourceDto.builder()
                                .value("strict")
                                .build())
                        .build());

        Map<String, Object> testCaseData = Map.of("expected", "correct answer");
        Map<String, Object> extractedColumns = Map.of("answer", "model response");

        Map<String, Object> result = resolver.resolveBindings(bindings, testCaseData, extractedColumns);

        assertThat(result)
                .hasSize(3)
                .containsEntry("reference", "correct answer")
                .containsEntry("actual", "model response")
                .containsEntry("mode", "strict");
    }

    @Test
    @DisplayName("Should parse bindings JSON string")
    void shouldParseBindingsJsonString() {
        String json = """
                [
                    {"property": "ref", "source": {"$type": "TestCase", "columnName": "col1"}},
                    {"property": "val", "source": {"$type": "Constant", "value": 42}}
                ]
                """;

        List<MetricParameterBindingDto> bindings = resolver.parseBindings(json);

        assertThat(bindings).hasSize(2);
        assertThat(bindings.get(0).getProperty()).isEqualTo("ref");
        assertThat(bindings.get(0).getSource()).isInstanceOf(TestCaseBindingSourceDto.class);
        assertThat(bindings.get(1).getProperty()).isEqualTo("val");
        assertThat(bindings.get(1).getSource()).isInstanceOf(ConstantBindingSourceDto.class);
    }

    @Test
    @DisplayName("Should return empty list for null bindings JSON")
    void shouldReturnEmptyListForNullBindingsJson() {
        assertThat(resolver.parseBindings(null)).isEmpty();
        assertThat(resolver.parseBindings("")).isEmpty();
    }

    @Test
    @DisplayName("Should parse JSON map string")
    void shouldParseJsonMapString() {
        String json = """
                {"col1": "value1", "col2": 42}
                """;

        Map<String, Object> result = resolver.parseJsonMap(json);

        assertThat(result).containsEntry("col1", "value1").containsEntry("col2", 42);
    }
}
