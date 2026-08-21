package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.TestSuiteRunConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunUpdateDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Test Suite Runs", description = "Test Suite Run management endpoints")
public class TestSuiteRunController {

    private final TestSuiteRunService testSuiteRunService;
    private final PaginationParamResolver paginationParamResolver;
    private final CsvDelimiterParser csvDelimiterParser;

    @PostMapping("/api/v1/test-suites/{testSuiteId}/runs")
    @Operation(
            summary = "Trigger a test suite run",
            description = "Creates and triggers a new test suite run asynchronously. "
                    + "Returns 409 SUITE_HAS_NO_DATASET when the suite is unbound (no dataset assigned) — "
                    + "this check fires before the suite-validity check, so an unbound suite always "
                    + "reports SUITE_HAS_NO_DATASET regardless of validation state.")
    @ApiResponse(responseCode = "202", description = "Run created and dispatched")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    @ApiResponse(responseCode = "400", description = "Invalid run configuration")
    @ApiResponse(responseCode = "409", description = "Suite has no dataset (SUITE_HAS_NO_DATASET)")
    @ApiResponse(responseCode = "429", description = "Concurrent run limit exceeded")
    public ResponseEntity<TestSuiteRunResponseDto> createRun(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Valid @RequestBody TestSuiteRunRequestDto request) {
        TestSuiteRunResponseDto response = testSuiteRunService.createRun(testSuiteId, request.getRunConfig());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping(
            value = "/api/v1/test-suites/{testSuiteId}/runs/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import eval results from a CSV file and evaluate them",
            description = "Creates a run from a batch of already-produced eval results (raw model responses) for "
                    + "an existing, dataset-bound test suite, then asynchronously runs metric evaluation and score "
                    + "computation against them — Phase 1 (deployment invocation) is never performed.\n\n"
                    + "**Request**: multipart/form-data with a `file` part (CSV), an optional `testRunName` part, "
                    + "and an optional `delimiter` part (single ASCII character, default comma).\n\n"
                    + "**Reserved CSV columns** (exact, case-sensitive, 19 total): `testCaseId`, `testCaseName`, "
                    + "`runIndex`, `testCaseData`, `requestBody`, `responseBody`, `responseStatusCode`, "
                    + "`executionStatus`, `startedAt`, `completedAt`, `traceId`, `retryCount`, `logDetails`, "
                    + "`extractedColumns`, `extractionWarnings`, `requestIndex`, `totalRequests`, `turnIndex`, "
                    + "`totalTurns`. Every column is reserved — there is no mapping from an arbitrary header to a "
                    + "`testCaseData` field; `testCaseData` is itself a required reserved column carrying the "
                    + "row's test-case data as a JSON object, validated against the dataset schema when "
                    + "configured (a per-turn field is type-checked when present but never required). Headers "
                    + "that do not match a reserved column name are ignored.\n\n"
                    + "**Identity columns** (`requestIndex`, `totalRequests`, `turnIndex`, `totalTurns`) are "
                    + "optional: an absent header or blank cell defaults to `0`/`1`/`0`/`1` (a single-request, "
                    + "single-turn row). When present, `requestIndex`/`turnIndex` must be non-negative, "
                    + "`totalRequests`/`totalTurns` must be at least 1, and each index must be less than its "
                    + "total.\n\n"
                    + "**Name-derived identity**: when a row supplies no `testCaseId`, its persisted id is "
                    + "derived from `testCaseName` — every row naming the same test case within one uploaded "
                    + "file shares one generated id, so a multi-request or multi-turn repetition's rows group "
                    + "together the same way a live run's do. A row with neither `testCaseId` nor `testCaseName` "
                    + "is rejected with HTTP 400. A supplied `testCaseId` is always used verbatim.\n\n"
                    + "**JSON columns**: `testCaseData` (required), `requestBody`, `responseBody`, "
                    + "`logDetails`, `extractedColumns` (default `{}`), and `extractionWarnings` (default `[]`) "
                    + "are parsed as JSON when non-blank; a malformed cell is reported as a row-level validation "
                    + "error. `executionStatus` must be one of `SUCCESS`, `FAILED`, `TIMEOUT`, `ERROR`.\n\n"
                    + "**Example** (a 2-request chain: `requestIndex=0` produces `configId`, `requestIndex=1` "
                    + "produces `answer`; the identity columns are optional — omitting them here would default "
                    + "every row to a single-request row):\n\n"
                    + "```csv\n"
                    + "testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData,"
                    + "extractedColumns,requestIndex,totalRequests\n"
                    + "chain-case,0,SUCCESS,1000,1500,\"{\"\"prompt\"\":\"\"hi\"\"}\",\"{\"\"configId\"\":7}\",0,2\n"
                    + "chain-case,0,SUCCESS,1500,2000,\"{\"\"prompt\"\":\"\"hi\"\"}\","
                    + "\"{\"\"answer\"\":\"\"hello\"\"}\",1,2\n"
                    + "```\n\n"
                    + "Same not-found/unbound-dataset/invalid-config/concurrency/name-uniqueness guards as creating a "
                    + "normal run. Validation is all-or-nothing: any row violation rejects the whole request and "
                    + "creates no run.")
    @ApiResponse(responseCode = "202", description = "Run created, results persisted, and evaluation dispatched")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    @ApiResponse(responseCode = "400", description = "Invalid CSV, malformed rows, or constraint violations")
    @ApiResponse(responseCode = "409", description = "Suite has no dataset (SUITE_HAS_NO_DATASET), or duplicate name")
    @ApiResponse(responseCode = "429", description = "Concurrent run limit exceeded")
    public ResponseEntity<TestSuiteRunResponseDto> importResults(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "CSV file with eval results") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Optional run name (max 255 characters)")
                    @RequestParam(required = false)
                    @Size(max = 255)
                    String testRunName,
            @Parameter(description = "CSV delimiter. Single ASCII character. Default: comma.")
                    @RequestParam(defaultValue = ",")
                    String delimiter) {
        validateImportFile(file);
        char delim = csvDelimiterParser.parse(delimiter);
        try {
            TestSuiteRunResponseDto response = testSuiteRunService.importResultsAndEvaluate(
                    testSuiteId, testRunName, file.getInputStream(), file.getSize(), delim);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (IOException e) {
            throw new ValidationException("Failed to read file: " + e.getMessage());
        }
    }

    @GetMapping("/api/v1/test-suite-runs")
    @Operation(
            summary = "List test suite runs",
            description = "Retrieves test suite runs with filtering, sorting, and pagination")
    @ApiResponse(responseCode = "200", description = "Runs retrieved successfully")
    public PageResponseDto<TestSuiteRunResponseDto> listRuns(
            @Parameter(description = "Page number") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort keys")
                    @RequestParam(name = "sort", required = false)
                    @Size(max = TestSuiteRunConstants.MAX_SORT_PARAMS)
                    List<String> sort,
            @Parameter(description = "Filter conditions") @FilterParam(max = TestSuiteRunConstants.MAX_FILTER_PARAMS)
                    List<String> filter,
            @Parameter(
                            description =
                                    "When true, includes totalElements and totalPages in the response. Default: false.")
                    @RequestParam(required = false)
                    Boolean includeTotalCount) {

        int resolvedPage = paginationParamResolver.resolvePage(page);
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return testSuiteRunService.listRuns(resolvedPage, resolvedSize, sort, filter, includeTotalCount);
    }

    @GetMapping("/api/v1/test-suite-runs/{id}")
    @Operation(summary = "Get a test suite run by ID", description = "Retrieves a single test suite run")
    @ApiResponse(responseCode = "200", description = "Run found")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public TestSuiteRunResponseDto getRun(@Parameter(description = "Run ID") @PathVariable UUID id) {
        return testSuiteRunService.getRun(id);
    }

    @GetMapping("/api/v1/test-suite-runs/{id}/costs")
    @Operation(
            summary = "Get average test-case and metric-evaluation cost for a run",
            description = "Queries dial-adas usage logs for the run and returns the average per-call price "
                    + "for test-case execution calls and metric-evaluation (judge model) calls. A phase with "
                    + "no matching usage-log rows returns null for that average.")
    @ApiResponse(responseCode = "200", description = "Costs computed")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "502", description = "dial-adas unreachable or returned an error")
    @ApiResponse(responseCode = "504", description = "dial-adas request timed out")
    public RunCostsResponseDto getRunCosts(@Parameter(description = "Run ID") @PathVariable UUID id) {
        return testSuiteRunService.getRunCosts(id);
    }

    @PatchMapping("/api/v1/test-suite-runs/{id}")
    @Operation(
            summary = "Update test suite run properties",
            description = "Updates mutable properties of a test suite run (currently testRunName)")
    @ApiResponse(responseCode = "200", description = "Run updated")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "409", description = "Duplicate test run name")
    public TestSuiteRunResponseDto updateRun(
            @Parameter(description = "Run ID") @PathVariable UUID id,
            @Valid @RequestBody TestSuiteRunUpdateDto updateDto) {
        return testSuiteRunService.updateRunName(id, updateDto.getTestRunName());
    }

    @PostMapping("/api/v1/test-suite-runs/{id}/cancel")
    @Operation(summary = "Cancel a test suite run", description = "Cancels a PENDING or RUNNING test suite run")
    @ApiResponse(responseCode = "200", description = "Cancellation requested")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "409", description = "Cannot cancel terminal run")
    public TestSuiteRunResponseDto cancelRun(@Parameter(description = "Run ID") @PathVariable UUID id) {
        return testSuiteRunService.cancelRun(id);
    }

    @DeleteMapping("/api/v1/test-suite-runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a test suite run", description = "Deletes a test suite run (terminal status only)")
    @ApiResponse(responseCode = "204", description = "Run deleted")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "409", description = "Cannot delete non-terminal run")
    public void deleteRun(@Parameter(description = "Run ID") @PathVariable UUID id) {
        testSuiteRunService.deleteRun(id);
    }

    private static void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("File is required and must not be empty");
        }
    }
}
