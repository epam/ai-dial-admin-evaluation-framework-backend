package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.JsonataProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DashjoinJsonataEvaluationService Tests")
class DashjoinJsonataEvaluationServiceTest {

    private DashjoinJsonataEvaluationService service;

    @BeforeEach
    void setUp() {
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        service = new DashjoinJsonataEvaluationService(new ObjectMapper(), jsonataProperties);
    }

    // --- validateExpression tests (task 5.3) ---

    @Test
    @DisplayName("valid expression passes validation without exception")
    void validateExpression_validExpression_noException() {
        service.validateExpression("choices[0].message.content");
    }

    @Test
    @DisplayName("valid nested expression passes validation")
    void validateExpression_nestedPath_noException() {
        service.validateExpression("usage.total_tokens");
    }

    @Test
    @DisplayName("simple field name passes validation")
    void validateExpression_singleField_noException() {
        service.validateExpression("score");
    }

    // --- invalid expression tests (task 5.4) ---

    @Test
    @DisplayName("invalid expression with unclosed bracket throws ValidationException")
    void validateExpression_unclosedBracket_throwsValidationException() {
        assertThatThrownBy(() -> service.validateExpression("choices[0.message.content"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("invalid expression throws ValidationException containing parse error info")
    void validateExpression_invalidSyntax_throwsWithMessage() {
        assertThatThrownBy(() -> service.validateExpression("choices[0.message.content"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid JSONata expression");
    }

    // --- evaluate tests (task 9.2) ---

    @Test
    @DisplayName("evaluates nested array path against JSON data")
    void evaluate_nestedPath_returnsValue() {
        String json = """
                {"choices": [{"message": {"content": "Hello"}}]}
                """;
        Object result = service.evaluate("choices[0].message.content", json);
        assertThat(result).isEqualTo("Hello");
    }

    @Test
    @DisplayName("evaluates numeric field")
    void evaluate_numericField_returnsNumber() {
        String json = """
                {"usage": {"total_tokens": 42}}
                """;
        Object result = service.evaluate("usage.total_tokens", json);
        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("returns null for missing path")
    void evaluate_missingPath_returnsNull() {
        String json = """
                {"usage": {"total_tokens": 42}}
                """;
        Object result = service.evaluate("usage.missing_field", json);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("returns null when jsonData is null")
    void evaluate_nullJsonData_returnsNull() {
        Object result = service.evaluate("choices[0].message.content", null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("returns null when jsonData is blank")
    void evaluate_blankJsonData_returnsNull() {
        Object result = service.evaluate("choices[0].message.content", "  ");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("throws ValidationException for invalid expression even during evaluate")
    void evaluate_invalidExpression_throwsValidationException() {
        assertThatThrownBy(() -> service.evaluate("choices[0.message", "{}")).isInstanceOf(ValidationException.class);
    }

    // --- evaluate(expression, jsonData, bindings) tests (WP1 task 1.1 / 1.4) ---

    @Test
    @DisplayName("bindings overload: a bound Map variable is reachable as $name")
    void evaluateWithBindings_mapValue_reachableAsFrameVariable() {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("history", Map.of("role", "user", "content", "hi"));

        Object result = service.evaluate("$history.role", null, bindings);

        assertThat(result).isEqualTo("user");
    }

    @Test
    @DisplayName("bindings overload: a bound List variable is reachable as $name")
    void evaluateWithBindings_listValue_reachableAsFrameVariable() {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("items", List.of(1, 2, 3));

        Object result = service.evaluate("$items[1]", null, bindings);

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("bindings overload: a bound String variable is reachable as $name")
    void evaluateWithBindings_stringValue_reachableAsFrameVariable() {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("greeting", "hello");

        Object result = service.evaluate("$greeting", null, bindings);

        assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("bindings overload: a bound Number variable is reachable as $name")
    void evaluateWithBindings_numberValue_reachableAsFrameVariable() {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("count", 42);

        Object result = service.evaluate("$count + 1", null, bindings);

        assertThat(result).isEqualTo(43);
    }

    @Test
    @DisplayName("bindings overload: a name absent from the map is unbound (undefined -> Java null)")
    void evaluateWithBindings_absentName_isUndefined() {
        Object result = service.evaluate("$missing", null, new HashMap<>());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName(
            "bindings overload: a Java null VALUE in the map is bound as explicit JSON null, distinguishable from unbound via $exists")
    void evaluateWithBindings_nullValueInMap_bindsExplicitJsonNull() {
        Map<String, Object> boundNull = new HashMap<>();
        boundNull.put("history", null);
        Map<String, Object> unbound = new HashMap<>();

        Object boundResult = service.evaluate("$exists($history)", null, boundNull);
        Object unboundResult = service.evaluate("$exists($history)", null, unbound);

        assertThat(boundResult).isEqualTo(true);
        assertThat(unboundResult).isEqualTo(false);
    }

    @Test
    @DisplayName(
            "bindings overload: $append($history, [1]) with $history bound to a null map value yields [null, 1] (real null-append semantics)")
    void evaluateWithBindings_nullValueInMap_appendYieldsNullPrepended() {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("history", null);

        Object result = service.evaluate("$append($history, [1])", null, bindings);

        ArrayList<Object> expected = new ArrayList<>();
        expected.add(null);
        expected.add(1);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("bindings overload: jsonData null/blank makes the root document an empty object, not null")
    void evaluateWithBindings_nullJsonData_rootIsEmptyObject() {
        Object result = service.evaluate("$keys($)", null, new HashMap<>());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("bindings overload: exceeding configured runtime bounds throws IllegalStateException")
    void evaluateWithBindings_runawayRecursion_throwsIllegalStateException() {
        JsonataProperties boundedProperties = new JsonataProperties();
        boundedProperties.setEvaluationTimeoutMs(2000L);
        boundedProperties.setMaxRecursionDepth(50);
        DashjoinJsonataEvaluationService boundedService =
                new DashjoinJsonataEvaluationService(new ObjectMapper(), boundedProperties);

        assertThatThrownBy(() -> boundedService.evaluate("($f := function(){$f()}; $f())", null, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("bindings overload: a normal expression under generous bounds is unaffected")
    void evaluateWithBindings_trivialExpression_succeedsUnderGenerousBounds() {
        Object result = service.evaluate("1 + 1", null, new HashMap<>());

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("2-arg evaluate: exceeding configured runtime bounds throws IllegalStateException")
    void evaluate_runawayRecursion_throwsIllegalStateException() {
        JsonataProperties boundedProperties = new JsonataProperties();
        boundedProperties.setEvaluationTimeoutMs(2000L);
        boundedProperties.setMaxRecursionDepth(50);
        DashjoinJsonataEvaluationService boundedService =
                new DashjoinJsonataEvaluationService(new ObjectMapper(), boundedProperties);

        assertThatThrownBy(() -> boundedService.evaluate("($f := function(){$f()}; $f())", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
