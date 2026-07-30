package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DashjoinJsonataEvaluationService Tests")
class DashjoinJsonataEvaluationServiceTest {

    private DashjoinJsonataEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new DashjoinJsonataEvaluationService(new ObjectMapper());
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
}
