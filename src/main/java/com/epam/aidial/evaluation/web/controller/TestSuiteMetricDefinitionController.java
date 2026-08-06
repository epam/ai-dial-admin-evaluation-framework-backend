package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.dto.AggregatedMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/test-suites/{testSuiteId}/metric-definitions")
@RequiredArgsConstructor
@Tag(name = "Test Suite Metric Definitions", description = "Manage metric definitions within a test suite")
public class TestSuiteMetricDefinitionController {

    private final TestSuiteMetricDefinitionService service;
    private final PaginationParamResolver paginationParamResolver;

    @PostMapping
    @Operation(
            summary = "Create a metric definition",
            description = "Creates a new metric definition in the test suite. "
                    + "The client must supply metricDeclarationVersionId explicitly.")
    @ApiResponse(
            responseCode = "201",
            description = "Metric definition created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteMetricDefinitionResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Test suite or metric declaration version not found")
    @ApiResponse(responseCode = "409", description = "Duplicate metric definition name in suite")
    @ResponseStatus(HttpStatus.CREATED)
    public TestSuiteMetricDefinitionResponseDto create(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Valid @RequestBody TestSuiteMetricDefinitionRequestDto dto) {
        return service.create(testSuiteId, dto);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get a metric definition by ID",
            description = "Retrieves a single metric definition belonging to the test suite")
    @ApiResponse(
            responseCode = "200",
            description = "Metric definition found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteMetricDefinitionResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Metric definition not found")
    public TestSuiteMetricDefinitionResponseDto getById(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Metric definition ID") @PathVariable UUID id) {
        return service.getById(testSuiteId, id);
    }

    @GetMapping("/{id}/aggregated")
    @Operation(
            summary = "Get an aggregated metric definition",
            description = "Retrieves a metric definition enriched with the full metric declaration "
                    + "and metric declaration version details (including schemas) in a single response")
    @ApiResponse(
            responseCode = "200",
            description = "Aggregated metric definition found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AggregatedMetricDefinitionResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Metric definition not found")
    public AggregatedMetricDefinitionResponseDto getAggregatedById(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Metric definition ID") @PathVariable UUID id) {
        return service.getAggregatedById(testSuiteId, id);
    }

    @GetMapping
    @Operation(
            summary = "List metric definitions",
            description = "Lists metric definitions for a test suite with pagination, filtering, and sorting")
    @ApiResponse(
            responseCode = "200",
            description = "Metric definitions retrieved",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    public PageResponseDto<TestSuiteMetricDefinitionResponseDto> list(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Page number") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort keys")
                    @RequestParam(name = "sort", required = false)
                    @Size(max = ValidationConstants.MAX_LIST_SORT_PARAMS)
                    List<String> sort,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(description = "When true, includes totalElements and totalPages. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeTotalCount) {

        int resolvedPage = paginationParamResolver.resolvePage(page);
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return service.list(testSuiteId, resolvedPage, resolvedSize, sort, filter, includeTotalCount);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update a metric definition",
            description = "Updates an existing metric definition. "
                    + "The client must supply metricDeclarationVersionId explicitly.")
    @ApiResponse(
            responseCode = "200",
            description = "Metric definition updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteMetricDefinitionResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Metric definition or metric declaration version not found")
    @ApiResponse(responseCode = "409", description = "Duplicate metric definition name in suite")
    public TestSuiteMetricDefinitionResponseDto update(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Metric definition ID") @PathVariable UUID id,
            @Valid @RequestBody TestSuiteMetricDefinitionRequestDto dto) {
        return service.update(testSuiteId, id, dto);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a metric definition", description = "Deletes a metric definition from the test suite")
    @ApiResponse(responseCode = "204", description = "Metric definition deleted")
    @ApiResponse(responseCode = "404", description = "Metric definition not found")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Metric definition ID") @PathVariable UUID id) {
        service.delete(testSuiteId, id);
    }
}
