package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConditionExpressionEvaluatorTest {

    private ConditionExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        evaluator = new ConditionExpressionEvaluator(new DashjoinJsonataEvaluationService(objectMapper), objectMapper);
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
    @DisplayName("Blank condition always runs")
    void blankConditionRuns() {
        assertThat(evaluator.evaluate(null, ctx("{}", "{}", 0, 1)).isRun()).isTrue();
        assertThat(evaluator.evaluate("  ", ctx("{}", "{}", 0, 1)).isRun()).isTrue();
    }

    @Test
    @DisplayName("Boolean true runs, boolean false skips")
    void booleanOutcomes() {
        assertThat(evaluator
                        .evaluate("response.score > 0.5", ctx("{}", "{\"score\":0.9}", 0, 1))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("response.score > 0.5", ctx("{}", "{\"score\":0.1}", 0, 1))
                        .isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("Non-boolean result is a condition error")
    void nonBooleanIsError() {
        assertThat(evaluator
                        .evaluate("response.score", ctx("{}", "{\"score\":0.9}", 0, 1))
                        .isError())
                .isTrue();
    }

    @Test
    @DisplayName("turn.last is true only on the final turn")
    void turnLastSelectsFinalTurn() {
        assertThat(evaluator.evaluate("turn.last", ctx("{}", "{}", 2, 3)).isRun())
                .isTrue();
        assertThat(evaluator.evaluate("turn.last", ctx("{}", "{}", 1, 3)).isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("Single-turn result is its own last turn")
    void singleTurnIsLast() {
        assertThat(evaluator.evaluate("turn.last", ctx("{}", "{}", 0, 1)).isRun())
                .isTrue();
    }

    @Test
    @DisplayName("Present-null column is distinguishable from missing via $exists")
    void presentNullPreserved() {
        assertThat(evaluator
                        .evaluate("$exists(response.answer)", ctx("{}", "{\"answer\":null}", 0, 1))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("$exists(response.answer)", ctx("{}", "{}", 0, 1))
                        .isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("Malformed condition is rejected at validate time")
    void malformedRejected() {
        assertThatThrownBy(() -> evaluator.validate("this is (not valid")).isInstanceOf(ValidationException.class);
    }
}
