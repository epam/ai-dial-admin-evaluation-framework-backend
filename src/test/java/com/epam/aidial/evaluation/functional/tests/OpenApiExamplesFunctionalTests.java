package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Verifies the try-it-out streaming examples and schema in the generated OpenAPI document. */
public abstract class OpenApiExamplesFunctionalTests extends BaseFunctionalTest {

    private static final String JSON = "application/json";

    @Test
    @DisplayName("both try-it-out SSE examples show assembled chat responses and their parsed chunks")
    void tryItOutSseExamplesShowAssembledBodyAndEvents() {
        final JsonNode apiDocs = fetchApiDocs();

        assertSseExample(
                apiDocs, "/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out", "fin", "ance");
        assertSseExample(apiDocs, "/api/v1/test-suites/{testSuiteId}/try-it-out", "The weather is ", "sunny.");
    }

    @Test
    @DisplayName("try-it-out response schema describes assembled bodies and parsed event diagnostics")
    void tryItOutSchemaDescribesStreamingAndNonStreamingBodies() {
        final JsonNode properties = fetchApiDocs()
                .path("components")
                .path("schemas")
                .path("TryItOutCoreResponseDto")
                .path("properties");

        assertThat(properties.path("body").path("description").asString())
                .contains(
                        "evaluation-equivalent assembled JSON value",
                        "partial text as a JSON string",
                        "structured events-envelope fallback",
                        "assembly fails and output is unavailable",
                        "diagnostic events envelope",
                        "error status retained",
                        "Assembled SSE JSON object, array, scalar and null values retain their types",
                        "non-SSE HTTP",
                        "null root body and null map properties are omitted",
                        "MCP",
                        "tool-result document");
        assertThat(properties.path("events").path("description").asString())
                .contains(
                        "Parsed SSE events in receipt order",
                        "independently of the assembled body",
                        "empty array",
                        "parsed JSON",
                        "raw string",
                        "not byte-verbatim transport data",
                        "Omitted for non-SSE HTTP and MCP");
    }

    private static void assertSseExample(JsonNode apiDocs, String path, String firstChunk, String secondChunk) {
        final JsonNode example = apiDocs.path("paths")
                .path(path)
                .path("post")
                .path("responses")
                .path("200")
                .path("content")
                .path(JSON)
                .path("examples")
                .path("sse")
                .path("value");
        assertThat(example.isObject()).as("SSE example attached to %s", path).isTrue();
        final JsonNode response = example.path("response");
        assertThat(response.path("statusCode").asInt()).isEqualTo(200);
        assertThat(response.path("streaming").asBoolean()).isTrue();
        final JsonNode body = response.path("body");
        assertThat(body.isObject()).isTrue();
        assertThat(body.propertyNames()).containsExactly("choices");
        final JsonNode choices = body.path("choices");
        assertThat(choices.isArray()).isTrue();
        assertThat(choices.size()).isEqualTo(1);
        final JsonNode choice = choices.get(0);
        assertThat(choice.path("message").path("role").asString()).isEqualTo("assistant");
        assertThat(choice.path("message").path("content").asString()).isEqualTo(firstChunk + secondChunk);
        assertThat(choice.path("finish_reason").asString()).isEqualTo("stop");
        assertThat(choice.path("index").asInt()).isZero();

        final JsonNode events = response.path("events");
        assertThat(events.isArray()).isTrue();
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.get(0).path("event").asString()).isEqualTo("message");
        assertThat(events.get(1).path("event").asString()).isEqualTo("message");
        assertThat(events.get(0)
                        .path("data")
                        .path("choices")
                        .get(0)
                        .path("delta")
                        .path("content")
                        .asString())
                .isEqualTo(firstChunk);
        assertThat(events.get(1)
                        .path("data")
                        .path("choices")
                        .get(0)
                        .path("delta")
                        .path("content")
                        .asString())
                .isEqualTo(secondChunk);
    }

    private JsonNode fetchApiDocs() {
        final ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(apiDocs.getBody());
    }
}
