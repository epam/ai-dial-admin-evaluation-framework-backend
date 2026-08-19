package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateQueryDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds dial-adas {@code "mode": "aggregate"} queries scoped to a run's usage-log rows for a given
 * execution phase, filtering on {@code request_tags.baggage} containing both {@code eval.run.id=<runId>}
 * and {@code eval.phase=<phase>}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class RunCostQueryBuilder {

    private static final String ENTITY = "dial_usage_log";
    private static final String AGGREGATE_MODE = "aggregate";
    private static final String REQUEST_TAGS_FIELD = "request_tags";
    private static final String BAGGAGE_KEY = "baggage";
    private static final String TOTAL_PRICE_FIELD = "total_price";
    private static final String AVG_COST_ALIAS = "avg_cost";

    private final ObjectMapper objectMapper;

    public AdasAggregateQueryDto buildAggregateQuery(UUID runId, String phase) {
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("op", "and");
        ArrayNode filterArgs = filter.putArray("args");
        filterArgs.add(baggageContainsCondition(TracingConstants.EVAL_RUN_ID + "=" + runId));
        filterArgs.add(baggageContainsCondition(TracingConstants.EVAL_PHASE + "=" + phase));

        return AdasAggregateQueryDto.builder()
                .entity(ENTITY)
                .mode(AGGREGATE_MODE)
                .filter(filter)
                .groupBy(List.of())
                .select(List.of(countExpr(), avgCostExpr()))
                .build();
    }

    private ObjectNode baggageContainsCondition(String substring) {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("op", "co");
        ArrayNode args = condition.putArray("args");
        args.add(jsonExtractBaggageFn());
        args.add(stringValue(substring));
        return condition;
    }

    private ObjectNode jsonExtractBaggageFn() {
        ObjectNode fn = objectMapper.createObjectNode();
        fn.put("type", "fn");
        fn.put("name", "json_extract_string");
        ArrayNode args = fn.putArray("args");
        args.add(field(REQUEST_TAGS_FIELD));
        args.add(stringValue(BAGGAGE_KEY));
        return fn;
    }

    private ObjectNode countExpr() {
        ObjectNode expr = objectMapper.createObjectNode();
        ObjectNode fn = objectMapper.createObjectNode();
        fn.put("type", "fn");
        fn.put("name", "count");
        fn.putArray("args");
        expr.set("expr", fn);
        return expr;
    }

    private ObjectNode avgCostExpr() {
        ObjectNode fn = objectMapper.createObjectNode();
        fn.put("type", "fn");
        fn.put("name", "avg");
        ArrayNode args = fn.putArray("args");
        args.add(field(TOTAL_PRICE_FIELD));

        ObjectNode expr = objectMapper.createObjectNode();
        expr.set("expr", fn);
        expr.put("as", AVG_COST_ALIAS);
        return expr;
    }

    private ObjectNode field(String name) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "field");
        node.put("name", name);
        return node;
    }

    private ObjectNode stringValue(String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "value");
        node.put("value_type", "string");
        node.put("value", value);
        return node;
    }
}
