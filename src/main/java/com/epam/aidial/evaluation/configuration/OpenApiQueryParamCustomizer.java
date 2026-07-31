package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterSpec;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.SortSpec;
import com.epam.aidial.evaluation.data.db.repository.sql.SortWhitelists;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Auto-generates rich OpenAPI parameter descriptions for filter, sort, and pagination
 * query parameters from existing {@code FilterWhitelists}, {@code SortWhitelists},
 * and {@code PaginationProperties}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class OpenApiQueryParamCustomizer implements OpenApiCustomizer {

    private final PaginationProperties paginationProperties;

    private enum PaginationType {
        OFFSET,
        CURSOR,
        NONE
    }

    private record EndpointParamConfig(
            FilterSpec filterSpec, SortSpec sortSpec, PaginationType paginationType, String filterNote) {
        EndpointParamConfig(FilterSpec filterSpec, SortSpec sortSpec, PaginationType paginationType) {
            this(filterSpec, sortSpec, paginationType, null);
        }
    }

    private static final Map<String, EndpointParamConfig> REGISTRY = Map.ofEntries(
            Map.entry(
                    "/api/v1/datasets",
                    new EndpointParamConfig(
                            FilterWhitelists.DATASETS,
                            SortWhitelists.DATASETS,
                            PaginationType.OFFSET,
                            "Note: `visibility` is not a filterable field. The server hard-filters this "
                                    + "endpoint to PUBLIC datasets only — PRIVATE datasets are accessible "
                                    + "by id but never appear in this list.")),
            Map.entry(
                    "/api/v1/test-suites",
                    new EndpointParamConfig(
                            FilterWhitelists.TEST_SUITES, SortWhitelists.TEST_SUITES, PaginationType.OFFSET)),
            Map.entry(
                    "/api/v1/datasets/{datasetId}/test-cases",
                    new EndpointParamConfig(
                            FilterWhitelists.TEST_CASES, SortWhitelists.TEST_CASES, PaginationType.OFFSET)),
            Map.entry(
                    "/api/v1/datasets/{datasetId}/test-cases/export.csv",
                    new EndpointParamConfig(FilterWhitelists.TEST_CASES, null, PaginationType.NONE)),
            Map.entry(
                    "/api/v1/test-suites/{testSuiteId}/metric-definitions",
                    new EndpointParamConfig(
                            FilterWhitelists.METRIC_DEFINITIONS,
                            SortWhitelists.METRIC_DEFINITIONS,
                            PaginationType.OFFSET)),
            Map.entry(
                    "/api/v1/metric-declarations",
                    new EndpointParamConfig(
                            FilterWhitelists.METRIC_DECLARATIONS,
                            SortWhitelists.METRIC_DECLARATIONS,
                            PaginationType.OFFSET)),
            Map.entry(
                    "/api/v1/test-suite-runs",
                    new EndpointParamConfig(
                            FilterWhitelists.TEST_SUITE_RUNS, SortWhitelists.TEST_SUITE_RUNS, PaginationType.OFFSET)),
            Map.entry(
                    "/api/v1/analytics/test-case-results",
                    new EndpointParamConfig(FilterWhitelists.ANALYTICS_RESULTS, null, PaginationType.CURSOR)),
            Map.entry(
                    "/api/v1/analytics/eval-summaries",
                    new EndpointParamConfig(FilterWhitelists.EVAL_SUMMARIES, null, PaginationType.CURSOR)),
            Map.entry(
                    "/api/v1/analytics/eval-summaries/export/preview",
                    new EndpointParamConfig(FilterWhitelists.EVAL_SUMMARIES, null, PaginationType.NONE)));

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach(this::customisePath);
    }

    private void customisePath(String path, PathItem pathItem) {
        EndpointParamConfig config = REGISTRY.get(path);
        if (config == null) {
            return;
        }
        pathItem.readOperationsMap().forEach((method, operation) -> customiseOperation(operation, config));
    }

    private void customiseOperation(Operation operation, EndpointParamConfig config) {
        List<Parameter> params = operation.getParameters();
        if (params == null) {
            return;
        }
        for (Parameter param : params) {
            customiseParameter(param, config);
        }
    }

    private void customiseParameter(Parameter param, EndpointParamConfig config) {
        switch (param.getName()) {
            case "filter" -> applyFilterDescription(param, config);
            case "sort" -> applySortDescription(param, config);
            case "page" -> applyPageDescription(param, config);
            case "size" -> applySizeDescription(param, config);
            case "cursor" -> applyCursorDescription(param, config);
            default -> {
                // no-op for parameters not managed by this customizer
            }
        }
    }

    private void applyFilterDescription(Parameter param, EndpointParamConfig config) {
        if (config.filterSpec() != null) {
            String description = QueryParamDescriptionGenerator.generateFilterDescription(config.filterSpec());
            if (config.filterNote() != null) {
                description = description + "\n\n" + config.filterNote();
            }
            param.setDescription(description);
            param.setExample(QueryParamDescriptionGenerator.generateFilterExample(config.filterSpec()));
        }
    }

    private void applySortDescription(Parameter param, EndpointParamConfig config) {
        if (config.sortSpec() != null) {
            param.setDescription(QueryParamDescriptionGenerator.generateSortDescription(config.sortSpec()));
            param.setExample(QueryParamDescriptionGenerator.generateSortExample(config.sortSpec()));
        }
    }

    private void applyPageDescription(Parameter param, EndpointParamConfig config) {
        if (config.paginationType() == PaginationType.OFFSET) {
            param.setDescription(QueryParamDescriptionGenerator.generatePageDescription());
        }
    }

    private void applySizeDescription(Parameter param, EndpointParamConfig config) {
        if (config.paginationType() != PaginationType.NONE) {
            param.setDescription(QueryParamDescriptionGenerator.generateSizeDescription(
                    paginationProperties.getDefaultSize(), paginationProperties.getMaxSize()));
        }
    }

    private void applyCursorDescription(Parameter param, EndpointParamConfig config) {
        if (config.paginationType() == PaginationType.CURSOR) {
            param.setDescription(QueryParamDescriptionGenerator.generateCursorDescription());
        }
    }
}
