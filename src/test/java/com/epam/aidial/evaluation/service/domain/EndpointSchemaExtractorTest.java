package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class EndpointSchemaExtractorTest {

    private final EndpointSchemaExtractor extractor = new EndpointSchemaExtractor();

    @Test
    void shouldExtractParametersAndRequestBodyFields() {
        EndpointContractDto endpoint = EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/v1/test")
                .parameters(List.of(
                        ParameterDefinitionDto.builder()
                                .name("query")
                                .in(ParameterLocation.QUERY)
                                .required(true)
                                .schema(Map.of("type", "string"))
                                .build(),
                        ParameterDefinitionDto.builder()
                                .name("limit")
                                .in(ParameterLocation.QUERY)
                                .required(false)
                                .schema(Map.of("type", "integer"))
                                .build()))
                .requestBodySchema(JsonRequestBodySchemaDto.builder()
                        .schema(Map.of(
                                "type", "object",
                                "required", List.of("score"),
                                "properties",
                                        Map.of(
                                                "score", Map.of("type", "number"),
                                                "meta",
                                                        Map.of(
                                                                "type",
                                                                "object",
                                                                "properties",
                                                                Map.of("nested", Map.of("type", "string"))))))
                        .build())
                .build();

        List<FieldDefinitionDto> fields = extractor.extractParameterFields(endpoint);

        assertThat(fields)
                .extracting(FieldDefinitionDto::getName)
                .containsExactlyInAnyOrder("query", "limit", "score", "meta");
        Map<String, FieldDefinitionDto> byName =
                fields.stream().collect(Collectors.toMap(FieldDefinitionDto::getName, field -> field));
        assertThat(byName.get("query").getType()).isEqualTo(SchemaFieldType.STRING);
        assertThat(byName.get("query").isRequired()).isTrue();
        assertThat(byName.get("limit").getType()).isEqualTo(SchemaFieldType.INTEGER);
        assertThat(byName.get("limit").isRequired()).isFalse();
        assertThat(byName.get("score").getType()).isEqualTo(SchemaFieldType.NUMBER);
        assertThat(byName.get("score").isRequired()).isTrue();
        assertThat(byName.get("meta").getType()).isEqualTo(SchemaFieldType.OBJECT);
        assertThat(byName.get("meta").isRequired()).isFalse();
    }

    @Test
    void shouldReturnEmptyWhenEndpointIsNull() {
        assertThat(extractor.extractParameterFields(null)).isEmpty();
    }
}
