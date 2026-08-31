package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.service.DashjoinJsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataEvaluationService;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.ExpressionBindingSourceDto;
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
        ObjectMapper objectMapper = new ObjectMapper();
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        JsonataEvaluationService jsonataEvaluationService =
                new DashjoinJsonataEvaluationService(objectMapper, jsonataProperties);
        resolver = new BindingResolver(objectMapper, jsonataEvaluationService);
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

        Map<String, Object> result =
                resolver.resolveBindings(List.of(binding), testCaseData, extractedColumns, Map.of());

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

        Map<String, Object> result =
                resolver.resolveBindings(List.of(binding), testCaseData, extractedColumns, Map.of());

        assertThat(result).containsEntry("actual", "generated text");
    }

    @Test
    @DisplayName("Should resolve Constant binding to literal value")
    void shouldResolveConstantBinding() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("threshold")
                .source(ConstantBindingSourceDto.builder().value(0.8).build())
                .build();

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), Map.of());

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

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), Map.of()))
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

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), Map.of()))
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

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), testCaseData, Map.of(), Map.of());

        assertThat(result).containsKey("reference");
        assertThat(result.get("reference")).isNull();
    }

    @Test
    @DisplayName("Should return empty map for empty bindings")
    void shouldReturnEmptyMapForEmptyBindings() {
        Map<String, Object> result = resolver.resolveBindings(Collections.emptyList(), Map.of(), Map.of(), Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty map for null bindings")
    void shouldReturnEmptyMapForNullBindings() {
        Map<String, Object> result = resolver.resolveBindings(null, Map.of(), Map.of(), Map.of());

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

        Map<String, Object> result = resolver.resolveBindings(bindings, testCaseData, extractedColumns, Map.of());

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

    @Test
    @DisplayName("Should resolve Expression binding's value path from the _metrics frame")
    void shouldResolveExpressionBindingValuePath() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("score")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("$_metrics.judge.score.value")
                        .build())
                .build();

        Map<String, Object> frame = Map.of(
                "_metrics",
                Map.of("judge", Map.of("score", Map.of("value", 0.9, "details", Map.of("reason", "matched")))),
                "data",
                Map.of(),
                "response",
                Map.of());

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), frame);

        assertThat(result).containsEntry("score", 0.9);
    }

    @Test
    @DisplayName("Should resolve Expression binding's details path from the _metrics frame")
    void shouldResolveExpressionBindingDetailsPath() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("reason")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("$_metrics.judge.score.details.reason")
                        .build())
                .build();

        Map<String, Object> frame = Map.of(
                "_metrics",
                Map.of("judge", Map.of("score", Map.of("value", 0.9, "details", Map.of("reason", "matched")))),
                "data",
                Map.of(),
                "response",
                Map.of());

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), frame);

        assertThat(result).containsEntry("reason", "matched");
    }

    @Test
    @DisplayName("Should resolve Expression binding's array index over the data frame")
    void shouldResolveExpressionBindingArrayIndex() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("firstItem")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("$data.items[0]")
                        .build())
                .build();

        Map<String, Object> frame =
                Map.of("_metrics", Map.of(), "data", Map.of("items", List.of("first", "second")), "response", Map.of());

        Map<String, Object> result = resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), frame);

        assertThat(result).containsEntry("firstItem", "first");
    }

    @Test
    @DisplayName("Should throw when Expression binding references an undefined path")
    void shouldThrowForUndefinedExpressionReference() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("missing")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("$_metrics.notYetRun.score.value")
                        .build())
                .build();

        Map<String, Object> frame = Map.of("_metrics", Map.of(), "data", Map.of(), "response", Map.of());

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), frame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluated to undefined or null");
    }

    @Test
    @DisplayName("Should throw with the same undefined-or-null message when Expression binding resolves to an"
            + " explicit JSON null (indistinguishable from undefined once the engine maps NULL_VALUE to Java"
            + " null)")
    void shouldThrowForExplicitNullExpressionReference() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("score")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("$_metrics.judge.score.value")
                        .build())
                .build();

        Map<String, Object> valueHolder = new java.util.HashMap<>();
        valueHolder.put("value", null);
        Map<String, Object> frame = Map.of(
                "_metrics", Map.of("judge", Map.of("score", valueHolder)), "data", Map.of(), "response", Map.of());

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), frame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluated to undefined or null");
    }

    @Test
    @DisplayName("Should throw when Expression binding is syntactically malformed")
    void shouldThrowForMalformedExpression() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("broken")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("this is (not valid")
                        .build())
                .build();

        assertThatThrownBy(() -> resolver.resolveBindings(List.of(binding), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed to evaluate");
    }
}
