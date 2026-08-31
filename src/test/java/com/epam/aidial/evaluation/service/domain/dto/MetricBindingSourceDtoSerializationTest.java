package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MetricBindingSourceDto polymorphic (de)serialization")
class MetricBindingSourceDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Expression binding source round-trips through the $type discriminator")
    void expressionBindingSource_roundTrips() throws JacksonException {
        ExpressionBindingSourceDto dto = ExpressionBindingSourceDto.builder()
                .expression("$_metrics.`judge`.score.value")
                .build();

        String json = objectMapper.writeValueAsString(dto);
        MetricBindingSourceDto deserialized = objectMapper.readValue(json, MetricBindingSourceDto.class);

        assertThat(json).contains("\"$type\":\"Expression\"");
        assertThat(deserialized).isInstanceOf(ExpressionBindingSourceDto.class);
        assertThat(((ExpressionBindingSourceDto) deserialized).getExpression())
                .isEqualTo("$_metrics.`judge`.score.value");
    }

    @Test
    @DisplayName("A raw JSON body with $type Expression deserializes into ExpressionBindingSourceDto")
    void expressionBindingSource_deserializesFromRawJson() {
        String json = "{\"$type\": \"Expression\", \"expression\": \"data.expected\"}";

        MetricBindingSourceDto deserialized = objectMapper.readValue(json, MetricBindingSourceDto.class);

        assertThat(deserialized).isInstanceOf(ExpressionBindingSourceDto.class);
        assertThat(((ExpressionBindingSourceDto) deserialized).getExpression()).isEqualTo("data.expected");
    }

    @Test
    @DisplayName("A MetricParameterBindingDto nesting an Expression source round-trips")
    void metricParameterBindingDto_nestingExpressionSource_roundTrips() {
        MetricParameterBindingDto binding = MetricParameterBindingDto.builder()
                .property("reference")
                .source(ExpressionBindingSourceDto.builder()
                        .expression("response.answer")
                        .build())
                .build();

        String json = objectMapper.writeValueAsString(binding);
        MetricParameterBindingDto deserialized = objectMapper.readValue(json, MetricParameterBindingDto.class);

        assertThat(deserialized.getProperty()).isEqualTo("reference");
        assertThat(deserialized.getSource()).isInstanceOf(ExpressionBindingSourceDto.class);
        assertThat(((ExpressionBindingSourceDto) deserialized.getSource()).getExpression())
                .isEqualTo("response.answer");
    }
}
