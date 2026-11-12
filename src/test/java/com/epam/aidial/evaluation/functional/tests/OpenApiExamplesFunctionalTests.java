package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.OpenApiExampleCustomizer;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
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
        for (Map.Entry<String, JsonNode> response :
                operation.path("responses").properties()) {
            final JsonNode examples =
                    response.getValue().path("content").path(JSON).path("examples");
            examples.propertyNames()
                    .forEach(name -> names.add(prefix + "-response-" + response.getKey() + "-" + name + ".json"));
        }
    }

    private JsonNode fetchApiDocs() {
        final ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(apiDocs.getBody());
    }
}
