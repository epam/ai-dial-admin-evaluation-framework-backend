package com.epam.aidial.evaluation.client.metricprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsDescriptionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MetricsDescriptionDto deserialization")
class MetricsDescriptionDtoDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("deserializes when provider returns schema fields as JSON objects")
    void deserializesObjectSchemas() throws Exception {
        String json = """
                {"name":"accuracy","description":"Correctness metric",\
                "config_schema":{"type":"object","properties":{"threshold":{"type":"number"}}},\
                "input_schema":{},"output_schema":{"type":"array"}}
                """;
        MetricsDescriptionDto dto = objectMapper.readValue(json, MetricsDescriptionDto.class);

        assertThat(dto.getName()).isEqualTo("accuracy");
        assertThat(dto.getDescription()).isEqualTo("Correctness metric");
        assertThat(dto.getConfigSchema()).contains("\"type\":\"object\"");
        assertThat(dto.getConfigSchema()).contains("\"threshold\"");
        assertThat(dto.getInputSchema()).isEqualTo("{}");
        assertThat(dto.getOutputSchema()).contains("\"type\":\"array\"");
    }

    @Test
    @DisplayName("deserializes when provider returns schema fields as JSON strings")
    void deserializesStringSchemas() throws Exception {
        String json = """
                {"name":"exact_match","description":"Exact match",\
                "config_schema":"{}","input_schema":"{\\"type\\":\\"object\\"}","output_schema":"{}"}
                """;
        MetricsDescriptionDto dto = objectMapper.readValue(json, MetricsDescriptionDto.class);

        assertThat(dto.getName()).isEqualTo("exact_match");
        assertThat(dto.getConfigSchema()).isEqualTo("{}");
        assertThat(dto.getInputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(dto.getOutputSchema()).isEqualTo("{}");
    }
}
