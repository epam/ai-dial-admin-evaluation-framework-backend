package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBindingSourceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BindingResolver")
class BindingResolverTest {

    private BindingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BindingResolver(new ObjectMapper());
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
