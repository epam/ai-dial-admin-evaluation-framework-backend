package com.epam.aidial.evaluation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiQueryParamCustomizerTest {

    private final OpenApiQueryParamCustomizer customizer = createCustomizer(20, 100);

    @Test
    void shouldOverwriteFilterDescriptionForRegisteredPath() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/test-suites", List.of(new Parameter().name("filter").description("old")));

        customizer.customise(openApi);

        Parameter filter = findParam(openApi, "/api/v1/test-suites", "filter");
        assertThat(filter.getDescription()).contains("field:operator:value");
        assertThat(filter.getDescription()).contains("name");
        assertThat(filter.getExample()).isNotNull();
    }

    @Test
    void shouldOverwriteSortDescriptionForRegisteredPath() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/test-suites", List.of(new Parameter().name("sort").description("old")));

        customizer.customise(openApi);

        Parameter sort = findParam(openApi, "/api/v1/test-suites", "sort");
        assertThat(sort.getDescription()).contains("field[,asc|desc]");
        assertThat(sort.getExample()).isNotNull();
    }

    @Test
    void shouldOverwritePageAndSizeDescriptions() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/test-suites",
                List.of(
                        new Parameter().name("page").description("old"),
                        new Parameter().name("size").description("old")));

        customizer.customise(openApi);

        assertThat(findParam(openApi, "/api/v1/test-suites", "page").getDescription())
                .contains("0-indexed");
        assertThat(findParam(openApi, "/api/v1/test-suites", "size").getDescription())
                .contains("Default: 20")
                .contains("max: 100");
    }

    @Test
    void shouldOverwriteCursorDescriptionForAnalytics() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/analytics/test-case-results",
                List.of(
                        new Parameter().name("cursor").description("old"),
                        new Parameter().name("filter").description("old"),
                        new Parameter().name("size").description("old")));

        customizer.customise(openApi);

        assertThat(findParam(openApi, "/api/v1/analytics/test-case-results", "cursor")
                        .getDescription())
                .contains("nextCursor");
        assertThat(findParam(openApi, "/api/v1/analytics/test-case-results", "filter")
                        .getDescription())
                .contains("field:operator:value");
    }

    @Test
    void shouldNotOverwriteSortWhenSortSpecIsNull() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/analytics/test-case-results",
                List.of(new Parameter().name("sort").description("original")));

        customizer.customise(openApi);

        assertThat(findParam(openApi, "/api/v1/analytics/test-case-results", "sort")
                        .getDescription())
                .isEqualTo("original");
    }

    @Test
    void shouldNotTouchUnregisteredPaths() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/unknown",
                List.of(
                        new Parameter().name("filter").description("untouched"),
                        new Parameter().name("sort").description("untouched")));

        customizer.customise(openApi);

        assertThat(findParam(openApi, "/api/v1/unknown", "filter").getDescription())
                .isEqualTo("untouched");
        assertThat(findParam(openApi, "/api/v1/unknown", "sort").getDescription())
                .isEqualTo("untouched");
    }

    @Test
    void shouldNotOverwritePageForCsvExportEndpoint() {
        OpenAPI openApi = buildOpenApi(
                "/api/v1/datasets/{datasetId}/test-cases/export.csv",
                List.of(
                        new Parameter().name("filter").description("old"),
                        new Parameter().name("size").description("original")));

        customizer.customise(openApi);

        assertThat(findParam(openApi, "/api/v1/datasets/{datasetId}/test-cases/export.csv", "filter")
                        .getDescription())
                .contains("field:operator:value");
        assertThat(findParam(openApi, "/api/v1/datasets/{datasetId}/test-cases/export.csv", "size")
                        .getDescription())
                .isEqualTo("original");
    }

    @Test
    void shouldHandleNullPaths() {
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(null);

        customizer.customise(openApi);
        // no exception
    }

    @Test
    void shouldHandleNullParameters() {
        OpenAPI openApi = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/api/v1/test-suites", pathItem);
        openApi.setPaths(paths);

        customizer.customise(openApi);
        // no exception
    }

    private static OpenApiQueryParamCustomizer createCustomizer(int defaultSize, int maxSize) {
        PaginationProperties props = new PaginationProperties();
        props.setDefaultSize(defaultSize);
        props.setMaxSize(maxSize);
        return new OpenApiQueryParamCustomizer(props);
    }

    private static OpenAPI buildOpenApi(String path, List<Parameter> parameters) {
        OpenAPI openApi = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation operation = new Operation();
        operation.setParameters(new ArrayList<>(parameters));
        pathItem.setGet(operation);
        paths.addPathItem(path, pathItem);
        openApi.setPaths(paths);
        return openApi;
    }

    private static Parameter findParam(OpenAPI openApi, String path, String paramName) {
        return openApi.getPaths().get(path).getGet().getParameters().stream()
                .filter(pr -> paramName.equals(pr.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Parameter not found: " + paramName));
    }
}
