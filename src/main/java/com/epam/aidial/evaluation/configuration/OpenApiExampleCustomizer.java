package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads OpenAPI request/response examples from classpath resources and injects them into the spec
 * to avoid bloating controller annotations. Convention: {@code openapi/examples/{pathKey}-{method}-{type}-{name}.json}.
 *
 * <p>pathKey = path with '/' replaced by '-', leading '-' stripped (e.g. /api/v1/test-suites -> api-v1-test-suites).
 * type = "request" or "response-{status}" (e.g. response-201).
 * name = "minimal" or "full".
 */
@Slf4j
@Component
@LogExecution
public class OpenApiExampleCustomizer implements OpenApiCustomizer {

    private static final String EXAMPLES_BASE = "openapi/examples/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MEDIA_TYPE_JSON = "application/json";
    private static final List<String> EXAMPLE_NAMES =
            List.of("minimal", "full", "multi-turn", "mcp", "sse", "subset", "with-bodies", "jsonata-body", "chained");

    @Override
    public void customise(io.swagger.v3.oas.models.OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, pathItem) -> {
            String pathKey = pathToKey(path);
            pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                String method = httpMethod.name();
                injectRequestExamples(operation, pathKey, method);
                injectResponseExamples(operation, pathKey, method);
            });
        });
    }

    private static String pathToKey(String path) {
        // Trailing-wildcard mappings (slash-containing path values, e.g. deployment IDs) are registered
        // by SpringDoc with the '/**' intact. Dropping it keeps example filenames free of '*', which is
        // illegal on Windows and shell-glob-hostile everywhere.
        return path.replaceAll("/\\*\\*$", "")
                .replace("/", "-")
                .replaceFirst("^-", "")
                .replace("{", "")
                .replace("}", "");
    }

    private void injectRequestExamples(io.swagger.v3.oas.models.Operation operation, String pathKey, String method) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null || requestBody.getContent() == null) {
            return;
        }
        MediaType mediaType = requestBody.getContent().get(MEDIA_TYPE_JSON);
        if (mediaType == null) {
            return;
        }
        for (String name : EXAMPLE_NAMES) {
            String resourcePath = EXAMPLES_BASE + pathKey + "-" + method + "-request-" + name + ".json";
            String value = loadResource(resourcePath);
            if (value != null) {
                if (mediaType.getExamples() == null) {
                    mediaType.setExamples(new java.util.LinkedHashMap<>());
                }
                mediaType.getExamples().put(name, new Example().value(value));
            }
        }
    }

    private void injectResponseExamples(io.swagger.v3.oas.models.Operation operation, String pathKey, String method) {
        if (operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((status, apiResponse) -> {
            if (apiResponse.getContent() == null) {
                return;
            }
            MediaType mediaType = apiResponse.getContent().get(MEDIA_TYPE_JSON);
            if (mediaType == null) {
                return;
            }
            for (String name : EXAMPLE_NAMES) {
                String resourcePath =
                        EXAMPLES_BASE + pathKey + "-" + method + "-response-" + status + "-" + name + ".json";
                String value = loadResource(resourcePath);
                if (value != null) {
                    Object exampleValue = parseJson(value);
                    if (exampleValue != null) {
                        if (mediaType.getExamples() == null) {
                            mediaType.setExamples(new java.util.LinkedHashMap<>());
                        }
                        mediaType.getExamples().put(name, new Example().value(exampleValue));
                    }
                }
            }
        });
    }

    private Object parseJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            log.warn("Invalid JSON in OpenAPI example: {}", e.getMessage(), e);
            return null;
        }
    }

    private String loadResource(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                return null;
            }
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.debug("Could not load OpenAPI example {}: {}", resourcePath, e.getMessage(), e);
            return null;
        }
    }
}
