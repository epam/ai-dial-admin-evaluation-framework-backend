package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.OpenApiExampleCustomizer;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards {@code openapi/examples}: {@link OpenApiExampleCustomizer} silently skips a file whose name does not
 * match a registered operation, status, example name or {@code application/json} media type, so every file
 * must be found injected in {@code /v3/api-docs}.
 */
public abstract class OpenApiExamplesFunctionalTests extends BaseFunctionalTest {

    private static final String JSON = "application/json";

    @Test
    @DisplayName("every file under openapi/examples is injected into the generated OpenAPI document")
    void everyExampleFileIsInjected() throws IOException {
        final Set<String> exampleFiles = listExampleFiles();
        final Set<String> injected = injectedExampleFileNames(fetchApiDocs());

        assertThat(exampleFiles).as("example files on the classpath").isNotEmpty();
        final Set<String> notInjected = exampleFiles.stream()
                .filter(file -> !injected.contains(file))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(notInjected)
                .as("example files not injected into /v3/api-docs (stale path/status or misnamed)")
                .isEmpty();
    }

    @Test
    @DisplayName("POST /api/v1/costs/total exposes request examples and 200 minimal and no-usage examples")
    void totalCostEndpointExposesRequestAndResponseExamples() {
        final JsonNode operation =
                fetchApiDocs().path("paths").path("/api/v1/costs/total").path("post");

        // request examples are attached as raw JSON text (see OpenApiExampleCustomizer), not parsed objects
        assertThat(operation
                        .path("requestBody")
                        .path("content")
                        .path(JSON)
                        .path("examples")
                        .path("full")
                        .path("value")
                        .asString())
                .contains("deploymentIds", "applications/public/my-app__0.0.1");

        final JsonNode responseExamples = operation
                .path("responses")
                .path("200")
                .path("content")
                .path(JSON)
                .path("examples");
        assertThat(responseExamples.has("minimal")).as("200 minimal example").isTrue();
        assertThat(responseExamples.has("no-usage")).as("200 no-usage example").isTrue();
    }

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

    @Test
    @DisplayName("export preview example follows the manifest and documents ISO timestamps and typed cells")
    void exportPreviewExampleDocumentsTimestampsAndManifest() {
        final JsonNode paths = fetchApiDocs().path("paths");
        final JsonNode previewOperation =
                paths.path("/api/v1/analytics/eval-summaries/export/preview").path("get");
        final JsonNode exportOperation =
                paths.path("/api/v1/analytics/eval-summaries/export.csv").path("post");
        assertThat(previewOperation.path("description").asString())
                .contains(
                        "`createdAt` and `computedAt`",
                        "ISO 8601 UTC strings",
                        "exactly three fractional digits",
                        "null timestamps stay `null`");
        assertThat(exportOperation.path("description").asString())
                .contains("preview timestamps use the same ISO 8601 UTC format");

        final JsonNode example = previewOperation
                .path("responses")
                .path("200")
                .path("content")
                .path(JSON)
                .path("examples")
                .path("minimal")
                .path("value");
        assertThat(example.isArray()).isTrue();
        assertThat(example.size()).isEqualTo(3);
        final JsonNode headers = example.get(0);
        final List<String> expectedHeaders = List.of(
                "id",
                "testSuiteId",
                "testSuiteRunId",
                "testCaseRunResultId",
                "testCaseId",
                "testCaseName",
                "runIndex",
                "requestIndex",
                "turnIndex",
                "computationId",
                "createdAt",
                "computedAt",
                "executionStatus",
                "execDurationMs",
                "metricEvalDurationMs",
                "responseStatusCode",
                "data::prompt",
                "response::answer",
                "metric::Accuracy::score",
                "metric::Accuracy::explanation",
                "metricInfo::Accuracy::score",
                "metricInfo::Accuracy::explanation",
                "metricError::Accuracy",
                "extractionWarnings",
                "requestBody",
                "responseBody");
        assertThat(headers.isArray()).isTrue();
        assertThat(headers.size()).isEqualTo(expectedHeaders.size());
        for (int i = 0; i < expectedHeaders.size(); i++) {
            assertThat(headers.get(i).asString()).isEqualTo(expectedHeaders.get(i));
        }
        for (int i = 1; i < example.size(); i++) {
            final JsonNode row = example.get(i);
            assertThat(row.isArray()).isTrue();
            assertThat(row.size()).isEqualTo(headers.size());
            assertThat(row.get(10).isString()).isTrue();
            assertThat(row.get(10).asString()).isEqualTo("2026-05-25T10:22:12.226Z");
            assertThat(row.get(11).isString()).isTrue();
            assertThat(row.get(11).asString()).isEqualTo("2026-05-25T10:22:13.226Z");
            assertThat(row.get(15).isNumber()).isTrue();
        }
        final JsonNode first = example.get(1);
        assertThat(first.get(13).asInt()).isEqualTo(482);
        assertThat(first.get(14).asInt()).isEqualTo(125);
        assertThat(first.get(18).isNumber()).isTrue();
        assertThat(first.get(18).asDouble()).isEqualTo(0.95);
        assertThat(first.get(20).isObject()).isTrue();
        assertThat(example.get(2).get(22).isObject()).isTrue();
        assertThat(example.get(2).get(23).isArray()).isTrue();
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

    private static Set<String> listExampleFiles() throws IOException {
        final Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath*:openapi/examples/*.json");
        return Arrays.stream(resources)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** File names the customizer would have loaded for every example present in the document. */
    private static Set<String> injectedExampleFileNames(JsonNode apiDocs) {
        final Set<String> names = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : apiDocs.path("paths").properties()) {
            final String pathKey = OpenApiExampleCustomizer.pathToKey(path.getKey());
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                final String prefix = pathKey + "-" + operation.getKey().toUpperCase(Locale.ROOT);
                collectRequestExamples(operation.getValue(), prefix, names);
                collectResponseExamples(operation.getValue(), prefix, names);
            }
        }
        return names;
    }

    private static void collectRequestExamples(JsonNode operation, String prefix, Set<String> names) {
        final JsonNode examples =
                operation.path("requestBody").path("content").path(JSON).path("examples");
        examples.propertyNames().forEach(name -> names.add(prefix + "-request-" + name + ".json"));
    }

    private static void collectResponseExamples(JsonNode operation, String prefix, Set<String> names) {
        for (Map.Entry<String, JsonNode> response : operation.path("responses").properties()) {
            final JsonNode examples =
                    response.getValue().path("content").path(JSON).path("examples");
            examples.propertyNames()
                    .forEach(name -> names.add(prefix + "-response-" + response.getKey() + "-" + name + ".json"));
        }
    }

    @Test
    @DisplayName("schedule endpoints expose create and edit examples, response examples and page parameter docs")
    void scheduleEndpointsExposeExamplesAndPageParameterDocs() {
        final JsonNode paths = fetchApiDocs().path("paths");
        final JsonNode create = paths.path("/api/v1/test-suites/{testSuiteId}/schedules");
        final JsonNode createPost = create.path("post");
        final JsonNode edit = paths.path("/api/v1/schedules/{scheduleId}").path("put");

        assertThat(exampleNames(createPost.path("requestBody").path("content").path(JSON)))
                .as("create request examples")
                .containsExactlyInAnyOrder("minimal", "full");
        assertThat(exampleNames(
                        createPost.path("responses").path("201").path("content").path(JSON)))
                .as("create 201 response examples")
                .containsExactlyInAnyOrder("minimal", "full");
        assertThat(exampleNames(edit.path("requestBody").path("content").path(JSON)))
                .as("edit request examples")
                .containsExactly("full");
        assertThat(paths.path("/api/v1/schedules/{scheduleId}/runs")
                        .path("get")
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path(JSON)
                        .path("examples")
                        .has("full"))
                .as("fire history 200 example")
                .isTrue();
        assertThat(parameterDescription(create.path("get"), "page"))
                .as("suite schedule list page parameter is documented by the query-param customizer")
                .startsWith("0-indexed page number");
        assertThat(parameterDescription(
                        paths.path("/api/v1/schedules/{scheduleId}/runs").path("get"), "size"))
                .as("fire history size parameter is documented by the query-param customizer")
                .startsWith("Page size. Default:");
    }

    private static Set<String> exampleNames(JsonNode mediaType) {
        final Set<String> names = new TreeSet<>();
        mediaType.path("examples").propertyNames().forEach(names::add);
        return names;
    }

    private static String parameterDescription(JsonNode operation, String parameterName) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (parameterName.equals(parameter.path("name").asString())) {
                return parameter.path("description").asString();
            }
        }
        return "";
    }

    private JsonNode fetchApiDocs() {
        final ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(apiDocs.getBody());
    }
}
