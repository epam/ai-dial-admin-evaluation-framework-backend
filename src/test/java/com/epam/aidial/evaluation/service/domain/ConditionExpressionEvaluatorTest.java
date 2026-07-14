package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
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
        evaluator = new ConditionExpressionEvaluator(jsonata, objectMapper);
    }

    private ConditionContext ctx(String dataJson, String responseJson) {
        return ConditionContext.builder()
                .dataJson(dataJson)
                .responseJson(responseJson)
                .build();
    }

    private ConditionContext ctx(String dataJson, String responseJson, int turnIndex, int totalTurns) {
        return ConditionContext.builder()
                .dataJson(dataJson)
                .responseJson(responseJson)
                .turnIndex(turnIndex)
                .totalTurns(totalTurns)
                .build();
    }

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
    @DisplayName("Whitespace around a condition is trimmed before evaluation")
    void evaluateTrimsWhitespace() {
        ConditionDecision decision = evaluator.evaluate("  response.score > 0.5  ", ctx("{}", "{\"score\":0.8}"));
        assertThat(decision.isRun()).isTrue();
    }

    @Test
    @DisplayName("turn.last is true on the final turn → RUN")
    void evaluateTurnLastOnFinalTurn() {
        ConditionDecision decision = evaluator.evaluate("turn.last", ctx("{}", "{}", 2, 3));
        assertThat(decision.isRun()).isTrue();
    }

    @Test
    @DisplayName("turn.last is false on a non-final turn → SKIP")
    void evaluateTurnLastOnNonFinalTurn() {
        ConditionDecision decision = evaluator.evaluate("turn.last", ctx("{}", "{}", 1, 3));
        assertThat(decision.isSkip()).isTrue();
    }

    @Test
    @DisplayName("A single-turn result is the last turn → RUN")
    void evaluateTurnLastSingleTurn() {
        ConditionDecision decision = evaluator.evaluate("turn.last", ctx("{}", "{}", 0, 1));
        assertThat(decision.isRun()).isTrue();
    }

    @Test
    @DisplayName("turn.index and turn.total are addressable")
    void evaluateTurnIndexAndTotal() {
        assertThat(evaluator.evaluate("turn.index = 2", ctx("{}", "{}", 2, 3)).isRun())
                .isTrue();
        assertThat(evaluator.evaluate("turn.total = 3", ctx("{}", "{}", 2, 3)).isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("turn.index = turn.total - 1", ctx("{}", "{}", 2, 3))
                        .isRun())
                .isTrue();
    }

    @Test
    @DisplayName("turn.last composes with a response predicate")
    void evaluateTurnLastComposedWithResponse() {
        ConditionDecision lastAndHigh =
                evaluator.evaluate("response.score > 0.5 and turn.last", ctx("{}", "{\"score\":0.8}", 2, 3));
        ConditionDecision notLast =
                evaluator.evaluate("response.score > 0.5 and turn.last", ctx("{}", "{\"score\":0.8}", 1, 3));

        assertThat(lastAndHigh.isRun()).isTrue();
        assertThat(notLast.isSkip()).isTrue();
    }
}
