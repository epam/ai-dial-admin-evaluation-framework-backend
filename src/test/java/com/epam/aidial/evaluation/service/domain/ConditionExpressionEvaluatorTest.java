package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ConditionExpressionEvaluator")
class ConditionExpressionEvaluatorTest {

    private ConditionExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonataEvaluationService jsonata = new DashjoinJsonataEvaluationService(objectMapper);
        ConditionFunctionRegistry registry = new ConditionFunctionRegistry(List.of());
        evaluator = new ConditionExpressionEvaluator(jsonata, registry, objectMapper);
    }

    private ConditionContext ctx(String dataJson, String responseJson) {
        return ConditionContext.builder()
                .dataJson(dataJson)
                .responseJson(responseJson)
                .build();
    }

    // ---- validate (write-time, hard failures) ----

    @Test
    @DisplayName("Blank condition validates as a no-op")
    void validateBlank() {
        evaluator.validate(null);
        evaluator.validate("   ");
    }

    @Test
    @DisplayName("Valid JSONata passes validation")
    void validateValidJsonata() {
        evaluator.validate("$exists(response.answer)");
    }

    @Test
    @DisplayName("Invalid JSONata syntax is rejected")
    void validateInvalidJsonata() {
        assertThatThrownBy(() -> evaluator.validate("$exists(")).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Unregistered bare name() is rejected")
    void validateUnknownFunction() {
        assertThatThrownBy(() -> evaluator.validate("isLastTurn()"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("isLastTurn");
    }

    // ---- evaluate (runtime, never throws) ----

    @Test
    @DisplayName("Blank condition runs the metric")
    void evaluateBlankRuns() {
        assertThat(evaluator.evaluate(null, ctx("{}", "{}")).isRun()).isTrue();
        assertThat(evaluator.evaluate("  ", ctx("{}", "{}")).isRun()).isTrue();
    }

    @Test
    @DisplayName("Condition true → RUN")
    void evaluateTrueRuns() {
        ConditionDecision decision =
                evaluator.evaluate("$exists(response.answer)", ctx("{}", "{\"answer\":\"Paris\"}"));
        assertThat(decision.isRun()).isTrue();
    }

    @Test
    @DisplayName("Condition false → SKIP")
    void evaluateFalseSkips() {
        ConditionDecision decision = evaluator.evaluate("$exists(response.answer)", ctx("{}", "{}"));
        assertThat(decision.isSkip()).isTrue();
    }

    @Test
    @DisplayName("Present-but-null column is distinguishable from a missing one")
    void evaluatePresentNullDistinctFromAbsent() {
        ConditionDecision present = evaluator.evaluate("$exists(response.answer)", ctx("{}", "{\"answer\":null}"));
        ConditionDecision absent = evaluator.evaluate("$exists(response.answer)", ctx("{}", "{}"));

        // Null is preserved (not dropped by NON_NULL), so a present-null column exists (RUN) while a
        // missing column does not (SKIP).
        assertThat(present.isRun()).isTrue();
        assertThat(absent.isSkip()).isTrue();
    }

    @Test
    @DisplayName("Non-boolean result → ERROR")
    void evaluateNonBooleanError() {
        ConditionDecision decision = evaluator.evaluate("response.answer", ctx("{}", "{\"answer\":\"Paris\"}"));
        assertThat(decision.isError()).isTrue();
    }

    @Test
    @DisplayName("Data namespace is addressable")
    void evaluateDataNamespace() {
        ConditionDecision decision = evaluator.evaluate("data.turns = 3", ctx("{\"turns\":3}", "{}"));
        assertThat(decision.isRun()).isTrue();
    }

    @Test
    @DisplayName("Response namespace is addressable")
    void evaluateResponseNamespace() {
        ConditionDecision decision = evaluator.evaluate("response.score > 0.5", ctx("{}", "{\"score\":0.8}"));
        assertThat(decision.isRun()).isTrue();
    }

    @Test
    @DisplayName("Condition is trimmed before custom-function detection")
    void evaluateTrimsBeforeDetection() {
        ConditionDecision decision = evaluator.evaluate("  isLastTurn()  ", ctx("{}", "{}"));
        assertThat(decision.isError()).isTrue();
        assertThat(decision.errorMessage()).contains("isLastTurn");
    }
}
