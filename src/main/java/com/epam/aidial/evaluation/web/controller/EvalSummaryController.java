package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportService;
import com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryDetailResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricAggregationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ResultCountResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/analytics/eval-summaries")
@RequiredArgsConstructor
@Tag(name = "Eval Summaries", description = "Evaluation summary analytics endpoints")
public class EvalSummaryController {

    private final EvalSummaryService evalSummaryService;
    private final EvalSummaryExportService evalSummaryExportService;
    private final PaginationParamResolver paginationParamResolver;

    @PostMapping
    @Operation(summary = "Batch write evaluation summaries")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalSummaryBatchWriteResponseDto batchCreate(@Valid @RequestBody EvalSummaryBatchWriteRequestDto request) {
        return evalSummaryService.batchCreate(request);
    }

    @GetMapping
    @Operation(summary = "List evaluation summaries with cursor-based pagination")
    public CursorPageResponseDto<EvalSummaryResponseDto> list(
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Cursor for next page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Sort parameter (not supported on this endpoint)") @RequestParam(required = false)
                    String sort,
            @Parameter(description = "Computation ID or 'latest'") @RequestParam(required = false) String computation) {
        if (sort != null) {
            throw new ValidationException("sort is not supported on this endpoint");
        }
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return evalSummaryService.listByFilter(filter, computation, cursor, resolvedSize);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get evaluation summary by ID")
    public EvalSummaryDetailResponseDto getById(@Parameter(description = "Eval summary ID") @PathVariable UUID id) {
        return evalSummaryService.getById(id);
    }

    @GetMapping("/count")
    @Operation(summary = "Count evaluation summaries matching filters")
    public ResultCountResponseDto count(
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(description = "Computation ID or 'latest'") @RequestParam(required = false) String computation) {
        return evalSummaryService.countByFilter(filter, computation);
    }

    @GetMapping("/aggregate")
    @Operation(summary = "Aggregate metric values")
    public MetricAggregationResponseDto aggregate(
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(description = "Computation ID or 'latest'") @RequestParam(required = false) String computation,
            @Parameter(description = "Metric paths in 'MetricName.outputName' format")
                    @RequestParam
                    @NotEmpty
                    @Size(max = 50)
                    List<String> metrics) {
        return evalSummaryService.aggregate(filter, computation, metrics);
    }

    @PostMapping(value = "/export.csv", produces = "text/csv; charset=UTF-8")
    @Operation(
            summary = "Stream evaluation summaries for a run as CSV",
            description = "Streams a UTF-8 CSV of evaluation summaries for the requested run. "
                    + "When `columns` is omitted, returns the full manifest minus `requestBody` "
                    + "and `responseBody`. Listing either body column in `columns` turns on the "
                    + "test_case_run_results JOIN projection. Use GET /export/preview to discover "
                    + "the full column manifest.")
    @ApiResponse(responseCode = "200", description = "CSV stream")
    @ApiResponse(
            responseCode = "400",
            description = "Validation error (malformed delimiter, unknown column name, "
                    + "out-of-whitelist filter, or malformed computation)")
    @ApiResponse(responseCode = "404", description = "Run or computation not found")
    @ApiResponse(responseCode = "409", description = "Run is not in a terminal state")
    @ApiResponse(
            responseCode = "422",
            description = "Run has no suite_snapshot (legacy runs are not exportable) "
                    + "or the snapshot version is not understood by the service")
    public void exportCsv(@Valid @RequestBody EvalSummaryExportRequestDto request, HttpServletResponse response) {
        evalSummaryExportService.exportToCsv(request, response);
    }

    @GetMapping(value = "/export/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Preview the export's column manifest and first ≤10 rows as JSON",
            description = "Returns an array whose first element is the full headers manifest "
                    + "(including `requestBody` and `responseBody` at the tail) and whose subsequent "
                    + "elements are up to 10 data rows with typed JSON cells (nested objects stay as "
                    + "JSON objects, numbers stay numeric, `null` stays `null`). Sole purpose: column "
                    + "discovery for the export endpoint.")
    @ApiResponse(responseCode = "200", description = "Preview rows")
    @ApiResponse(
            responseCode = "400",
            description = "Validation error (malformed computation or out-of-whitelist filter)")
    @ApiResponse(responseCode = "404", description = "Run or computation not found")
    @ApiResponse(responseCode = "409", description = "Run is not in a terminal state")
    @ApiResponse(
            responseCode = "422",
            description = "Run has no suite_snapshot (legacy runs are not exportable) "
                    + "or the snapshot version is not understood by the service")
    public List<List<Object>> previewExport(
            @Parameter(description = "TestSuiteRun identifier", required = true) @RequestParam UUID runId,
            @Parameter(description = "Computation ID or 'latest' (default)") @RequestParam(required = false)
                    String computation,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter) {
        return evalSummaryExportService.previewAsJson(runId, computation, filter);
    }
}
