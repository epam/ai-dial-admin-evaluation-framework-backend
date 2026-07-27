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

    private ConditionContext requestCtx(String responseJson, int requestIndex, String requestLabel) {
        return ConditionContext.builder()
                .dataJson("{}")
                .responseJson(responseJson)
                .turnIndex(0)
                .totalTurns(1)
                .requestIndex(requestIndex)
                .requestLabel(requestLabel)
                .build();
    }

    @Test
    @DisplayName("request.label targets exactly one chain request")
    void requestLabelTargetsOneRequest() {
        String condition = "request.label = \"invoke\"";

        assertThat(evaluator.evaluate(condition, requestCtx("{}", 1, "invoke")).isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate(condition, requestCtx("{}", 0, "configure"))
                        .isSkip())
                .isTrue();
        assertThat(evaluator
                        .evaluate(condition, requestCtx("{}", 2, "teardown"))
                        .isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("request.index targets exactly one chain request")
    void requestIndexTargetsOneRequest() {
        String condition = "request.index = 1";

        assertThat(evaluator.evaluate(condition, requestCtx("{}", 1, "invoke")).isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate(condition, requestCtx("{}", 0, "configure"))
                        .isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("A single-request suite sees request.index = 0 and its resolved default label")
    void singleRequestSeesStableRequestNamespace() {
        assertThat(evaluator
                        .evaluate("request.index = 0", requestCtx("{}", 0, "request-1"))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("request.label = \"request-1\"", requestCtx("{}", 0, "request-1"))
                        .isRun())
                .isTrue();
    }

    @Test
    @DisplayName("request combines with response in one expression")
    void requestCombinesWithResponse() {
        String condition = "request.label = \"invoke\" and $exists(response.answer)";

        assertThat(evaluator
                        .evaluate(condition, requestCtx("{\"answer\":\"42\"}", 1, "invoke"))
                        .isRun())
                .isTrue();
        assertThat(evaluator.evaluate(condition, requestCtx("{}", 1, "invoke")).isSkip())
                .isTrue();
        assertThat(evaluator
                        .evaluate(condition, requestCtx("{\"answer\":\"42\"}", 0, "configure"))
                        .isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("request combines with data in one expression")
    void requestCombinesWithData() {
        ConditionContext context = ConditionContext.builder()
                .dataJson("{\"category\":\"billing\"}")
                .responseJson("{}")
                .turnIndex(0)
                .totalTurns(1)
                .requestIndex(2)
                .requestLabel("invoke")
                .build();

        assertThat(evaluator
                        .evaluate("request.index = 2 and data.category = \"billing\"", context)
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("request.index = 1 and data.category = \"billing\"", context)
                        .isSkip())
                .isTrue();
    }

    @Test
    @DisplayName("request and turn namespaces coexist, both readable from one dictionary")
    void requestAndTurnCoexist() {
        ConditionContext context = ConditionContext.builder()
                .dataJson("{}")
                .responseJson("{}")
                .turnIndex(0)
                .totalTurns(1)
                .requestIndex(1)
                .requestLabel("invoke")
                .build();

        assertThat(evaluator
                        .evaluate("turn.last and request.label = \"invoke\"", context)
                        .isRun())
                .isTrue();
    }

    @Test
    @DisplayName("A null request label is present-but-null, and never equals a label being targeted")
    void nullRequestLabelIsPresentButNull() {
        // Only imported rows can carry a null label — the normalizer defaults every executed request's label.
        // The dictionary preserves explicit nulls (same contract as response columns), so $exists is true while
        // any equality test against a real label is false, which is what keeps a label-targeted metric off it.
        assertThat(evaluator
                        .evaluate("$exists(request.label)", requestCtx("{}", 0, null))
                        .isRun())
                .isTrue();
        assertThat(evaluator
                        .evaluate("request.label = \"invoke\"", requestCtx("{}", 0, null))
                        .isSkip())
                .isTrue();
    }
}
