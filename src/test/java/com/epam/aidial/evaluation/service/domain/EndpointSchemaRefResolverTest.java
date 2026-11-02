package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.UrlEncodedFormRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.util.jackson.HttpMethodSerializer;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

@DisplayName("Endpoint schema active reference path")
class EndpointSchemaRefResolverTest {

    private final EndpointSchemaRefResolver resolver = new EndpointSchemaRefResolver();
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(HttpMethod.class, new HttpMethodSerializer()))
            .build();

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("cyclicEndpoints")
    @DisplayName("Direct and indirect cycles fail with a circular reference error without changing input JSON")
    void shouldRejectCyclesWithoutChangingInput(String cycle, String location, EndpointContractDto endpoint) {
        final String before = objectMapper.writeValueAsString(endpoint);

        assertThatThrownBy(() -> resolver.resolve(endpoint))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Circular $ref detected: #/$defs/");

        assertThat(objectMapper.writeValueAsString(endpoint)).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acyclicEndpoints")
    @DisplayName("Repeated acyclic references resolve in independent branches without changing input JSON")
    void shouldResolveRepeatedReferencesWithoutChangingInput(String location, EndpointContractDto endpoint) {
        final String before = objectMapper.writeValueAsString(endpoint);

        final EndpointContractDto resolved = resolver.resolve(endpoint);
        final Map<String, Object> schema = resolvedSchema(location, resolved);

        assertThat(schema.get("properties"))
                .isEqualTo(Map.of("first", Map.of("type", "string"), "second", Map.of("type", "string")));
        assertThat(objectMapper.writeValueAsString(resolved)).doesNotContain("$ref");
        assertThat(resolved.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(resolved.getRelativeUrlPattern()).isEqualTo("/items");
        assertThat(resolved.getOperationId()).isEqualTo("items");
        assertThat(objectMapper.writeValueAsString(endpoint)).isEqualTo(before);
    }

    @Test
    @DisplayName("Null endpoints remain optional")
    void shouldPreserveNullEndpoint() {
        assertThat(resolver.resolve(null)).isNull();
    }

    private static Stream<Arguments> cyclicEndpoints() {
        final Map<String, Object> direct = Map.of(
                "$defs", Map.of("Node", Map.of("type", "object", "properties", Map.of("next", Map.of("$ref", "#/$defs/Node")))),
                "$ref", "#/$defs/Node");
        final Map<String, Object> indirect = Map.of(
                "$defs", Map.of("A", Map.of("$ref", "#/$defs/B"), "B", Map.of("$ref", "#/$defs/A")),
                "$ref", "#/$defs/A");
        return Stream.of("request-body", "urlencoded-body", "response-body", "parameter")
                .flatMap(location -> Stream.of(
                        Arguments.of("direct", location, endpoint(location, direct)),
                        Arguments.of("indirect A -> B -> A", location, endpoint(location, indirect))));
    }

    private static Stream<Arguments> acyclicEndpoints() {
        final Map<String, Object> schema = Map.of(
                "type", "object",
                "$defs", Map.of("Value", Map.of("type", "string")),
                "properties", Map.of("first", Map.of("$ref", "#/$defs/Value"), "second", Map.of("$ref", "#/$defs/Value")));
        return Stream.of("request-body", "urlencoded-body", "response-body", "parameter")
                .map(location -> Arguments.of(location, endpoint(location, schema)));
    }

    private static EndpointContractDto endpoint(String location, Map<String, Object> schema) {
        final EndpointContractDto endpoint = EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/items")
                .operationId("items")
                .build();
        switch (location) {
            case "request-body" -> endpoint.setRequestBodySchema(JsonRequestBodySchemaDto.builder().schema(schema).build());
            case "urlencoded-body" -> endpoint.setRequestBodySchema(UrlEncodedFormRequestBodySchemaDto.builder().schema(schema).build());
            case "response-body" -> endpoint.setResponseBodySchema(schema);
            case "parameter" -> endpoint.setParameters(List.of(ParameterDefinitionDto.builder()
                    .name("query")
                    .in(ParameterLocation.QUERY)
                    .required(true)
                    .schema(schema)
                    .build()));
            default -> throw new IllegalArgumentException(location);
        }
        return endpoint;
    }

    private static Map<String, Object> resolvedSchema(String location, EndpointContractDto endpoint) {
        return switch (location) {
            case "request-body" -> ((JsonRequestBodySchemaDto) endpoint.getRequestBodySchema()).getSchema();
            case "urlencoded-body" -> ((UrlEncodedFormRequestBodySchemaDto) endpoint.getRequestBodySchema()).getSchema();
            case "response-body" -> endpoint.getResponseBodySchema();
            case "parameter" -> endpoint.getParameters().getFirst().getSchema();
            default -> throw new IllegalArgumentException(location);
        };
    }
}
