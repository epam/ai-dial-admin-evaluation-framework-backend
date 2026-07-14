package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricErrorDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("MetricOutputMapper")
class MetricOutputMapperTest {

    private MetricOutputMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mapper = new MetricOutputMapper(objectMapper);
    }

    @Test
    @DisplayName("Should map value output without details")
    void shouldMapValueWithoutDetails() {
        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Accuracy", success("exact_match", Map.of("exact_match", valueOutput(BigDecimal.ONE, null))));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.path("Accuracy").path("exact_match").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(infos).isNull();
    }

    @Test
    @DisplayName("Should map value output with details")
    void shouldMapValueWithDetails() {
        Map<String, Object> details = Map.of("reason", "matches exactly");
        Map<String, TsmdEvaluationResult> tsmdResults = Map.of(
                "Accuracy", success("exact_match", Map.of("score", valueOutput(new BigDecimal("0.95"), details))));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.path("Accuracy").path("score").decimalValue()).isEqualByComparingTo(new BigDecimal("0.95"));
        assertThat(infos).isNotNull();
        assertThat(infos.path("Accuracy").path("score").path("reason").asString())
                .isEqualTo("matches exactly");
    }

    @Test
    @DisplayName("Should map error output to null value and error info")
    void shouldMapErrorOutput() {
        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Regex", success("regex_match", Map.of("regex_match", errorOutput("Invalid pattern"))));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.path("Regex").path("regex_match").isNull()).isTrue();
        assertThat(infos).isNotNull();
        assertThat(infos.path("Regex").path("regex_match").path("error").asString())
                .isEqualTo("Invalid pattern");
    }

    @Test
    @DisplayName(
            "Transport failure with real field names — null per field in metricValues, error per field in metricInfos")
    void shouldMapTransportFailureWithFieldNames() {
        Map<String, TsmdEvaluationResult> tsmdResults = Map.of(
                "Accuracy",
                new TsmdEvaluationResult.Failure(
                        new RuntimeException("Connection refused"), List.of("score", "confidence")));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.path("Accuracy").path("score").isNull()).isTrue();
        assertThat(values.path("Accuracy").path("confidence").isNull()).isTrue();
        assertThat(values.path("Accuracy").has("error")).isFalse();
        assertThat(infos).isNotNull();
        assertThat(infos.path("Accuracy").path("score").path("error").asString())
                .isEqualTo("Connection refused");
        assertThat(infos.path("Accuracy").path("confidence").path("error").asString())
                .isEqualTo("Connection refused");
    }

    @Test
    @DisplayName("Transport failure with empty field names — empty {} in metricValues, flat error in metricInfos")
    void shouldMapTransportFailureWithEmptyFieldNames() {
        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Accuracy", new TsmdEvaluationResult.Failure(new RuntimeException("Schema missing"), List.of()));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.path("Accuracy").isEmpty()).isTrue();
        assertThat(infos).isNotNull();
        assertThat(infos.path("Accuracy").path("error").asString()).isEqualTo("Schema missing");
    }

    @Test
    @DisplayName("Should merge multiple TSMDs")
    void shouldMergeMultipleTsmds() {
        Map<String, TsmdEvaluationResult> tsmdResults = new LinkedHashMap<>();
        tsmdResults.put("Accuracy", success("exact_match", Map.of("exact_match", valueOutput(BigDecimal.ONE, null))));
        tsmdResults.put(
                "Relevancy",
                success(
                        "answer_relevancy",
                        Map.of("score", valueOutput(new BigDecimal("0.8"), Map.of("reason", "good")))));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.has("Accuracy")).isTrue();
        assertThat(values.has("Relevancy")).isTrue();
        assertThat(values.path("Accuracy").path("exact_match").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(values.path("Relevancy").path("score").decimalValue()).isEqualByComparingTo(new BigDecimal("0.8"));
        assertThat(infos).isNotNull();
        assertThat(infos.has("Relevancy")).isTrue();
        assertThat(infos.has("Accuracy")).isFalse();
    }

    @Test
    @DisplayName("Mixed success and transport failure — both TSMDs in output")
    void shouldMixSuccessAndFailure() {
        Map<String, TsmdEvaluationResult> tsmdResults = new LinkedHashMap<>();
        tsmdResults.put("Accuracy", success("exact_match", Map.of("score", valueOutput(new BigDecimal("0.9"), null))));
        tsmdResults.put(
                "Relevancy",
                new TsmdEvaluationResult.Failure(new RuntimeException("Timeout"), List.of("relevance_score")));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.path("Accuracy").path("score").decimalValue()).isEqualByComparingTo(new BigDecimal("0.9"));
        assertThat(values.path("Relevancy").path("relevance_score").isNull()).isTrue();
        assertThat(infos).isNotNull();
        assertThat(infos.has("Accuracy")).isFalse();
        assertThat(infos.path("Relevancy").path("relevance_score").path("error").asString())
                .isEqualTo("Timeout");
    }

    @Test
    @DisplayName("ConditionError — omitted from metricValues, wholesale error in metricInfos")
    void shouldMapConditionErrorAsWholesaleError() {
        Map<String, TsmdEvaluationResult> tsmdResults = Map.of(
                "Relevancy",
                new TsmdEvaluationResult.ConditionError(
                        "Condition did not evaluate to a boolean: response.score", List.of("score")));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.has("Relevancy")).isFalse();
        assertThat(infos).isNotNull();
        assertThat(infos.path("Relevancy").path("error").asString())
                .isEqualTo("Condition did not evaluate to a boolean: response.score");
        assertThat(infos.path("Relevancy").has("score")).isFalse();
    }

    @Test
    @DisplayName("Success alongside ConditionError — only the successful metric has a value")
    void shouldMixSuccessAndConditionError() {
        Map<String, TsmdEvaluationResult> tsmdResults = new LinkedHashMap<>();
        tsmdResults.put("Accuracy", success("exact_match", Map.of("score", valueOutput(new BigDecimal("0.9"), null))));
        tsmdResults.put("Relevancy", new TsmdEvaluationResult.ConditionError("boom", List.of("relevance_score")));

        ObjectNode values = mapper.buildMetricValues(tsmdResults);
        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(values.has("Accuracy")).isTrue();
        assertThat(values.has("Relevancy")).isFalse();
        assertThat(infos).isNotNull();
        assertThat(infos.path("Relevancy").path("error").asString()).isEqualTo("boom");
    }

    @Test
    @DisplayName("Should return null metricInfos when all details are empty")
    void shouldReturnNullInfosWhenAllEmpty() {
        Map<String, TsmdEvaluationResult> tsmdResults = Map.of(
                "A", success("m1", Map.of("f1", valueOutput(BigDecimal.ONE, null))),
                "B", success("m2", Map.of("f2", valueOutput(BigDecimal.ZERO, null))));

        ObjectNode infos = mapper.buildMetricInfos(tsmdResults);

        assertThat(infos).isNull();
    }

    private TsmdEvaluationResult.Success success(String metricName, Map<String, MetricOutputDto> output) {
        EvaluationResponseDto response = EvaluationResponseDto.builder()
                .metricName(metricName)
                .output(output)
                .build();
        return new TsmdEvaluationResult.Success(response, List.of());
    }

    private MetricOutputFieldDto valueOutput(BigDecimal value, Map<String, Object> details) {
        return MetricOutputFieldDto.builder()
                .type("value")
                .value(value)
                .details(details)
                .build();
    }

    private MetricErrorDto errorOutput(String message) {
        return MetricErrorDto.builder().type("error").message(message).build();
    }
}
