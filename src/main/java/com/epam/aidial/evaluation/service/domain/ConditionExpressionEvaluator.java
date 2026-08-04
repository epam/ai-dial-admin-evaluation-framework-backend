package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.service.JsonataEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single entry point for metric-execution conditions. A condition is a JSONata expression evaluated
 * against a {@code {"data", "response", "turn", "request"}} dictionary (delegated to
 * {@link JsonataEvaluationService}).
 *
 * <p>{@link #validate} is used at write time (hard 400 on a malformed condition). {@link #evaluate}
 * is used at run time and never throws — it maps every outcome to a {@link ConditionDecision}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ConditionExpressionEvaluator {

    private static final String DATA_NAMESPACE = "data";
    private static final String RESPONSE_NAMESPACE = "response";
    private static final String TURN_NAMESPACE = "turn";
    private static final String TURN_INDEX = "index";
    private static final String TURN_TOTAL = "total";
    private static final String TURN_LAST = "last";
    private static final String REQUEST_NAMESPACE = "request";
    private static final String REQUEST_INDEX = "index";
    private static final String REQUEST_TOTAL = "total";
    private static final String REQUEST_LAST = "last";
    private static final String REQUEST_NAME = "name";

    private final JsonataEvaluationService jsonataEvaluationService;
    private final ObjectMapper objectMapper;

    /**
     * Validates a condition at write time. No-op for a null/blank condition. Rejects a syntactically
     * invalid JSONata expression via {@link ValidationException} → HTTP 400.
     */
    public void validate(String condition) {
        if (isBlank(condition)) {
            return;
        }
        jsonataEvaluationService.validateExpression(condition.trim());
    }

    /**
     * Evaluates a condition for a single test-case result. A blank condition always runs. Only a clean
     * boolean {@code true}/{@code false} maps to RUN/SKIP; any other result (non-boolean, null, or a
     * thrown error) maps to a surfaced ERROR. Never throws.
     */
    public ConditionDecision evaluate(String condition, ConditionContext context) {
        if (isBlank(condition)) {
            return ConditionDecision.run();
        }
        final String trimmed = condition.trim();
        try {
            final Object raw = jsonataEvaluationService.evaluate(trimmed, buildDictionaryJson(context));
            final Boolean result = (raw instanceof Boolean bool) ? bool : null;
            if (result == null) {
                return ConditionDecision.error("Condition did not evaluate to a boolean: " + trimmed);
            }
            return result ? ConditionDecision.run() : ConditionDecision.skip();
        } catch (ValidationException | IllegalStateException | JacksonException e) {
            return ConditionDecision.error("Condition evaluation failed: " + e.getMessage());
        }
    }

    /**
     * Serializes the context into {@code {"data": ..., "response": ..., "turn": {...}, "request": {...}}}.
     * Built from parsed {@code JsonNode} trees and serialized as an {@code ObjectNode} so explicit JSON
     * nulls are preserved (the shared {@code NON_NULL} mapper would drop null-valued map entries, making
     * a present-but-null column look absent — see AGENTS.md JSONB-null caveat).
     *
     * <p>The {@code turn} namespace carries the current turn's position so conditions can gate on it, e.g.
     * {@code turn.last} to run only on the final turn, or {@code turn.index}/{@code turn.total}. Because a
     * test case's turns are contiguous {@code 0..N-1}, {@code turn.last} is {@code index == total - 1}.
     * A single-turn result is {@code index=0, total=1, last=true}.
     *
     * <p>The {@code request} namespace mirrors {@code turn} for the suite's request chain position:
     * {@code request.index}/{@code request.total}/{@code request.last}, plus {@code request.name} — the
     * chain-order request label, {@code putNull} (not omitted) when the request at this position is
     * unlabelled so {@code $exists(request.name)} is honest. A single-request result is
     * {@code index=0, total=1, last=true}.
     */
    private String buildDictionaryJson(ConditionContext context) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.set(DATA_NAMESPACE, readTreeOrEmpty(context.dataJson()));
        root.set(RESPONSE_NAMESPACE, readTreeOrEmpty(context.responseJson()));

        final ObjectNode turn = objectMapper.createObjectNode();
        turn.put(TURN_INDEX, context.turnIndex());
        turn.put(TURN_TOTAL, context.totalTurns());
        turn.put(TURN_LAST, context.turnIndex() == context.totalTurns() - 1);
        root.set(TURN_NAMESPACE, turn);

        final ObjectNode request = objectMapper.createObjectNode();
        request.put(REQUEST_INDEX, context.requestIndex());
        request.put(REQUEST_TOTAL, context.totalRequests());
        request.put(REQUEST_LAST, context.requestIndex() == context.totalRequests() - 1);
        if (context.requestName() == null) {
            request.putNull(REQUEST_NAME);
        } else {
            request.put(REQUEST_NAME, context.requestName());
        }
        root.set(REQUEST_NAMESPACE, request);

        return objectMapper.writeValueAsString(root);
    }

    private JsonNode readTreeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            log.warn("Failed to parse condition dictionary JSON, treating as empty: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
