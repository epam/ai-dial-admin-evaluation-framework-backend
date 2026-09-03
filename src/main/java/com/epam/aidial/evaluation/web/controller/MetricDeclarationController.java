package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.MetricDeclarationService;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationWithLatestVersionResponseDto;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@LogExecution
@RequestMapping("/api/v1/metric-declarations")
@RequiredArgsConstructor
@Tag(name = "Metric Declarations", description = "Read-only metric declaration endpoints")
public class MetricDeclarationController {

    private final MetricDeclarationService metricDeclarationService;
    private final PaginationParamResolver paginationParamResolver;

    @GetMapping
    @Operation(
            summary = "Get all metric declarations",
            description = "Retrieves all metric declarations with pagination, filtering and sorting")
    @ApiResponse(
            responseCode = "200",
            description = "Metric declarations retrieved successfully",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    public PageResponseDto<MetricDeclarationResponseDto> getAll(
            @Parameter(description = "Page number") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort keys")
                    @RequestParam(name = "sort", required = false)
                    @Size(max = ValidationConstants.MAX_LIST_SORT_PARAMS)
                    List<String> sort,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(
                            description =
                                    "When true, includes totalElements and totalPages in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeTotalCount) {

        int resolvedPage = paginationParamResolver.resolvePage(page);
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return metricDeclarationService.getAll(resolvedPage, resolvedSize, sort, filter, includeTotalCount);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get a metric declaration by ID",
            description = "Retrieves a metric declaration by its unique identifier")
    @ApiResponse(
            responseCode = "200",
            description = "Metric declaration found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MetricDeclarationResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Metric declaration not found")
    public ResponseEntity<MetricDeclarationResponseDto> getById(
            @Parameter(description = "Metric declaration ID") @PathVariable UUID id) {

        MetricDeclarationResponseDto dto = metricDeclarationService.getById(id);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}/latest")
    @Operation(
            summary = "Get latest version of a metric declaration",
            description =
                    "Returns the latest schema version for the given metric declaration (by schema_version descending). 404 if declaration or no version exists.")
    @ApiResponse(
            responseCode = "200",
            description = "Latest version found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MetricDeclarationVersionResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Metric declaration not found or no version exists")
    public ResponseEntity<MetricDeclarationVersionResponseDto> getLatestVersion(
            @Parameter(description = "Metric declaration ID") @PathVariable UUID id) {

        MetricDeclarationVersionResponseDto dto = metricDeclarationService.getLatestVersion(id);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/versions/latest")
    @Operation(
            summary = "Get every metric declaration with its latest version",
            description = "Returns one item per metric declaration - the declaration itself, with its latest schema "
                    + "version (greatest schema_version) nested under latestVersion - ordered by metric declaration "
                    + "ID. Declarations that have no version yet are omitted; an empty array is returned when no "
                    + "versions exist at all.")
    @ApiResponse(
            responseCode = "200",
            description = "Latest versions retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    MetricDeclarationWithLatestVersionResponseDto
                                                                            .class))))
    public ResponseEntity<List<MetricDeclarationWithLatestVersionResponseDto>> getLatestVersions() {
        final List<MetricDeclarationWithLatestVersionResponseDto> latestVersions =
                metricDeclarationService.getLatestVersions();
        return ResponseEntity.ok(latestVersions);
    }
}
