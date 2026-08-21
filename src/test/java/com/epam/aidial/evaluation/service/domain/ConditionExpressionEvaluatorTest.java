package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.service.DashjoinJsonataEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConditionExpressionEvaluatorTest {

    private ConditionExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        evaluator = new ConditionExpressionEvaluator(
                new DashjoinJsonataEvaluationService(objectMapper, jsonataProperties), objectMapper);
    }

    private ConditionContext ctx(String dataJson, String responseJson, int turnIndex, int totalTurns) {
        return ConditionContext.builder()
                .dataJson(dataJson)
                .responseJson(responseJson)
                .turnIndex(turnIndex)
                .totalTurns(totalTurns)
                .build();
    }

    private ConditionContext requestCtx(int requestIndex, int totalRequests, String requestName) {
        return ConditionContext.builder()
                .dataJson("{}")
                .responseJson("{}")
                .turnIndex(0)
                .totalTurns(1)
                .requestIndex(requestIndex)
                .totalRequests(totalRequests)
                .requestName(requestName)
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

    @Test
    @DisplayName("request.index and request.total reflect the chain position")
    void requestIndexAndTotalReflectChainPosition() {
        assertThat(evaluator
                        .evaluate("request.index = 0 and request.total = 2", requestCtx(0, 2, null))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("request.index = 1 and request.total = 2", requestCtx(1, 2, null))
                        .isRun())
                .isTrue();
    }

    @Test
    @DisplayName("request.last is true only on the final request")
    void requestLastSelectsFinalRequest() {
        assertThat(evaluator.evaluate("request.last", requestCtx(1, 2, null)).isRun())
                .isTrue();
        assertThat(evaluator.evaluate("request.last", requestCtx(0, 2, null)).isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("Single-request result is its own last request")
    void singleRequestIsLast() {
        assertThat(evaluator.evaluate("request.last", requestCtx(0, 1, null)).isRun())
                .isTrue();
    }

    @Test
    @DisplayName("request.name resolves to the chain-order label")
    void requestNameResolvesLabel() {
        assertThat(evaluator
                        .evaluate("request.name = 'configure'", requestCtx(0, 2, "configure"))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("request.name = 'ask'", requestCtx(1, 2, "ask"))
                        .isRun())
                .isTrue();
    }

    @Test
    @DisplayName("request.name survives serialization as an explicit JSON null when the request is unlabelled")
    void requestNameNullSurvivesSerialization() {
        // $exists() is true for both a labelled and an unlabelled request — a present-null value is
        // still "present" (matches the existing response.answer:null precedent in presentNullPreserved()).
        assertThat(evaluator
                        .evaluate("$exists(request.name)", requestCtx(0, 1, "configure"))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("$exists(request.name)", requestCtx(0, 1, null))
                        .isRun())
                .isTrue();
        // The explicit null is a real JSONata null usable in a comparison — not an omitted key, which
        // would make an equality comparison against it evaluate to undefined (a condition ERROR) rather
        // than a clean boolean.
        assertThat(evaluator
                        .evaluate("request.name = null", requestCtx(0, 1, null))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("request.name = null", requestCtx(0, 1, "configure"))
                        .isSkip())
                .isTrue();
    }
}
