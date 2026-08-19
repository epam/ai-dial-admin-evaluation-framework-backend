package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateQueryDto;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("RunCostQueryBuilder")
class RunCostQueryBuilderTest {

    private static final UUID RUN_ID = UUID.fromString("1f810de3-cb9b-4e50-b9c5-794c41d99f6c");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RunCostQueryBuilder builder = new RunCostQueryBuilder(objectMapper);

    @Test
    @DisplayName("builds the execution-phase aggregate query")
    void buildsExecutionPhaseQuery() {
        AdasAggregateQueryDto query = builder.buildAggregateQuery(RUN_ID, TracingConstants.PHASE_EXECUTION);

        assertThat(query.getEntity()).isEqualTo("dial_usage_log");
        assertThat(query.getMode()).isEqualTo("aggregate");
        assertThat(query.getGroupBy()).isEmpty();

        JsonNode expectedFilter = objectMapper.readTree(
                """
                {
                  "op": "and",
                  "args": [
                    {
                      "op": "co",
                      "args": [
                        { "type": "fn", "name": "json_extract_string", "args": [
                            { "type": "field", "name": "request_tags" },
                            { "type": "value", "value_type": "string", "value": "baggage" }
                        ] },
                        { "type": "value", "value_type": "string", "value": "eval.run.id=1f810de3-cb9b-4e50-b9c5-794c41d99f6c" }
                      ]
                    },
                    {
                      "op": "co",
                      "args": [
                        { "type": "fn", "name": "json_extract_string", "args": [
                            { "type": "field", "name": "request_tags" },
                            { "type": "value", "value_type": "string", "value": "baggage" }
                        ] },
                        { "type": "value", "value_type": "string", "value": "eval.phase=execution" }
                      ]
                    }
                  ]
                }
                """);
        assertThat(query.getFilter()).isEqualTo(expectedFilter);

        JsonNode expectedSelect = objectMapper.readTree(
                """
                [
                  { "expr": { "type": "fn", "name": "count", "args": [] } },
                  { "expr": { "type": "fn", "name": "avg", "args": [ { "type": "field", "name": "total_price" } ] }, "as": "avg_cost" }
                ]
                """);
        JsonNode actualSelect = objectMapper.valueToTree(query.getSelect());
        assertThat(actualSelect).isEqualTo(expectedSelect);
    }

    @Test
    @DisplayName("builds the metric-evaluation-phase aggregate query with the same shape but a different phase value")
    void buildsMetricEvaluationPhaseQuery() {
        AdasAggregateQueryDto query = builder.buildAggregateQuery(RUN_ID, TracingConstants.PHASE_METRIC_EVALUATION);

        String filterJson = objectMapper.writeValueAsString(query.getFilter());
        assertThat(filterJson)
                .contains("\"value\":\"eval.phase=metric-evaluation\"")
                .contains("\"value\":\"eval.run.id=1f810de3-cb9b-4e50-b9c5-794c41d99f6c\"");
    }
}
