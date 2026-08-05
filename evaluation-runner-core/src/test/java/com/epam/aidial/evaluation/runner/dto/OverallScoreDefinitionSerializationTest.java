package com.epam.aidial.evaluation.runner.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("OverallScoreDefinition serialization")
class OverallScoreDefinitionSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Mean round-trips as {\"type\":\"mean\"}")
    void meanRoundTrip() throws JacksonException {
        String json = objectMapper.writeValueAsString(new Mean());
        OverallScoreDefinition deserialized = objectMapper.readValue(json, OverallScoreDefinition.class);

        assertThat(json).isEqualTo("{\"type\":\"mean\"}");
        assertThat(deserialized).isInstanceOf(Mean.class);
    }

    @Test
    @DisplayName("WeightedMean round-trips with its metric/weight rows")
    void weightedMeanRoundTrip() throws JacksonException {
        WeightedMean dto = new WeightedMean(List.of(
                new WeightedMetric("RAG Retrieval", "F1", new BigDecimal("1.0")),
                new WeightedMetric("RAG Retrieval", "Precision", new BigDecimal("3.0"))));

        String json = objectMapper.writeValueAsString(dto);
        OverallScoreDefinition deserialized = objectMapper.readValue(json, OverallScoreDefinition.class);

        assertThat(json).contains("\"type\":\"weighted_mean\"");
        assertThat(deserialized).isInstanceOf(WeightedMean.class);
        WeightedMean result = (WeightedMean) deserialized;
        assertThat(result.weights()).hasSize(2);
        assertThat(result.weights().get(0).metricName()).isEqualTo("RAG Retrieval");
        assertThat(result.weights().get(0).outputField()).isEqualTo("F1");
        assertThat(result.weights().get(0).weight()).isEqualByComparingTo("1.0");
        assertThat(result.weights().get(1).outputField()).isEqualTo("Precision");
        assertThat(result.weights().get(1).weight()).isEqualByComparingTo("3.0");
    }

    @Test
    @DisplayName("CustomFunction round-trips its raw expression object")
    void customFunctionRoundTrip() throws JacksonException {
        CustomFunction dto = new CustomFunction(Map.of(
                "entity",
                "eval_summaries",
                "mode",
                "aggregate",
                "select",
                List.of(Map.of(
                        "expr",
                        Map.of(
                                "type",
                                "fn",
                                "name",
                                "avg",
                                "args",
                                List.of(Map.of("type", "field", "name", "metric::Relevancy::score"))),
                        "as",
                        "value"))));

        String json = objectMapper.writeValueAsString(dto);
        OverallScoreDefinition deserialized = objectMapper.readValue(json, OverallScoreDefinition.class);

        assertThat(json).contains("\"type\":\"custom_function\"");
        assertThat(deserialized).isInstanceOf(CustomFunction.class);
        CustomFunction result = (CustomFunction) deserialized;
        assertThat(result.expression()).containsEntry("entity", "eval_summaries");
        assertThat(result.expression()).containsEntry("mode", "aggregate");
    }
}
