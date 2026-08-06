package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.CsvExportService;
import com.epam.aidial.evaluation.service.domain.CsvImportService;
import com.epam.aidial.evaluation.service.domain.TestCaseService;
import com.epam.aidial.evaluation.service.domain.ZipExportService;
import com.epam.aidial.evaluation.service.domain.ZipImportService;
import com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBatchPutItemDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/datasets/{datasetId}/test-cases")
@RequiredArgsConstructor
@Tag(name = "Test Cases", description = "TestCase CRUD and PATCH endpoints")
public class TestCaseController {

    private final TestCaseService testCaseService;
    private final CsvExportService csvExportService;
    private final CsvImportService csvImportService;
    private final ZipExportService zipExportService;
    private final ZipImportService zipImportService;
    private final PaginationParamResolver paginationParamResolver;
    private final CsvDelimiterParser csvDelimiterParser;

    @PostMapping
    @Operation(
            summary = "Create a test case",
            requestBody =
                    @RequestBody(
                            description = "Test case to create",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestCaseRequestDto.class))))
    @ApiResponse(
            responseCode = "201",
            description = "Test case created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestCaseResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseResponseDto create(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Valid @org.springframework.web.bind.annotation.RequestBody TestCaseRequestDto dto,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.create(datasetId, dto, includeWarnings);
    }

    @PostMapping(value = "import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Preview CSV or ZIP import (parse and validate without persisting)",
            description = "Accepts .csv or .zip files. ZIP archives must contain a test-cases.csv file.")
    @ApiResponse(responseCode = "200", description = "Preview with detected columns, sample rows, and warnings")
    @ApiResponse(responseCode = "400", description = "Empty/malformed CSV or file too large")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public CsvImportPreviewDto importPreview(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "CSV or ZIP file") @RequestParam("file") MultipartFile file,
            @Parameter(description = "CSV delimiter. Single ASCII character. Default: comma.")
                    @RequestParam(defaultValue = ",")
                    String delimiter,
            @Parameter(
                            description =
                                    "OVERRIDE: replace all. APPEND: add new rows. MERGE: add rows + new schema fields. Default: OVERRIDE.")
                    @RequestParam(defaultValue = "OVERRIDE")
                    CsvImportMode importMode,
            @Parameter(
                            description =
                                    "FAIL: HTTP 409 on collision. SKIP: skip, first wins. OVERRIDE: replace, last wins. Default: FAIL.")
                    @RequestParam(defaultValue = "FAIL")
                    CsvConflictStrategy conflictStrategy) {
        validateImportFile(file);
        char delim = csvDelimiterParser.parse(delimiter);
        try {
            if (isZipFile(file)) {
                return zipImportService.previewZip(
                        datasetId, file.getInputStream(), file.getSize(), delim, importMode, conflictStrategy);
            }
            return csvImportService.preview(
                    datasetId, file.getInputStream(), file.getSize(), delim, importMode, conflictStrategy);
        } catch (IOException e) {
            throw new ValidationException("Failed to read file: " + e.getMessage());
        }
    }

    @PostMapping(value = "import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import test cases from CSV or ZIP",
            description =
                    "Imports test cases from a CSV or ZIP file. "
                            + "ZIP archives must contain a test-cases.csv file and may include a files/ directory. "
                            + "importMode=OVERRIDE (default) deletes all existing test cases and replaces the schema. "
                            + "importMode=APPEND appends new rows while preserving existing ones. "
                            + "importMode=MERGE appends new rows and merges new schema fields. "
                            + "conflictStrategy controls name collisions: FAIL (default, HTTP 409), SKIP (first wins), OVERRIDE (last wins).")
    @ApiResponse(responseCode = "200", description = "Import result with counts and warnings")
    @ApiResponse(responseCode = "400", description = "Empty/malformed CSV or file too large")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    @ApiResponse(
            responseCode = "409",
            description = "Version conflict (If-Match) or name collision (conflictStrategy=FAIL)")
    public CsvImportResultDto importCsv(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "CSV or ZIP file") @RequestParam("file") MultipartFile file,
            @Parameter(description = "CSV delimiter. Single ASCII character. Default: comma.")
                    @RequestParam(defaultValue = ",")
                    String delimiter,
            @Parameter(
                            description =
                                    "OVERRIDE: replace all. APPEND: add new rows. MERGE: add rows + new schema fields. Default: OVERRIDE.")
                    @RequestParam(defaultValue = "OVERRIDE")
                    CsvImportMode importMode,
            @Parameter(
                            description =
                                    "FAIL: HTTP 409 on collision. SKIP: skip, first wins. OVERRIDE: replace, last wins. Default: FAIL.")
                    @RequestParam(defaultValue = "FAIL")
                    CsvConflictStrategy conflictStrategy,
            @Parameter(description = "Optional: current version (from ETag) for optimistic locking")
                    @RequestHeader(value = "If-Match", required = false)
                    String ifMatch) {
        validateImportFile(file);
        char delim = csvDelimiterParser.parse(delimiter);
        Long expectedVersion = parseVersionOptional(ifMatch);
        try {
            if (isZipFile(file)) {
                return zipImportService.importZip(
                        datasetId,
                        file.getInputStream(),
                        file.getSize(),
                        delim,
                        expectedVersion,
                        importMode,
                        conflictStrategy);
            }
            return csvImportService.importCsv(
                    datasetId,
                    file.getInputStream(),
                    file.getSize(),
                    delim,
                    expectedVersion,
                    importMode,
                    conflictStrategy);
        } catch (IOException e) {
            throw new ValidationException("Failed to read file: " + e.getMessage());
        }
    }

    private static Long parseVersionOptional(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String trimmed = ifMatch.trim().replaceAll("^\"|\"$", "");
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new ValidationException("If-Match must be a version number (or quoted version), got: " + ifMatch);
        }
    }

    private static boolean isZipFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".zip")) {
            return true;
        }
        String contentType = file.getContentType();
        return "application/zip".equals(contentType) || "application/x-zip-compressed".equals(contentType);
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("File is required and must not be empty");
        }
    }

    @GetMapping(
            value = "export.csv",
            produces = {"text/csv; charset=UTF-8", "application/zip"})
    @Operation(
            summary = "Export test cases as CSV or ZIP",
            description = "Exports as plain CSV by default. When the dataset schema contains FILE-type fields and "
                    + "materializeFiles is true (the default), exports as ZIP archive containing test-cases.csv "
                    + "and a files/ directory with referenced files downloaded from DIAL. "
                    + "Set materializeFiles=false to export raw DIAL file paths in a plain CSV.")
    @ApiResponse(responseCode = "200", description = "CSV file or ZIP archive")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public void exportCsv(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "CSV delimiter. Single ASCII character. Default: comma.")
                    @RequestParam(defaultValue = ",")
                    String delimiter,
            @Parameter(
                            description =
                                    "When true and FILE fields exist, embeds file bytes in a ZIP archive. "
                                            + "When false, exports raw DIAL file paths in CSV. Default: true when FILE fields exist.")
                    @RequestParam(required = false)
                    Boolean materializeFiles,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            HttpServletResponse response)
            throws IOException {
        char delim = csvDelimiterParser.parse(delimiter);
        boolean hasFiles = zipExportService.hasFileFields(datasetId);
        boolean doMaterialize = materializeFiles != null ? materializeFiles : hasFiles;

        if (doMaterialize && hasFiles) {
            response.setContentType("application/zip");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"test-cases-" + datasetId + ".zip\"");
            zipExportService.exportZip(datasetId, filter, delim, response.getOutputStream());
        } else {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"test-cases-" + datasetId + ".csv\"");
            csvExportService.exportCsv(datasetId, filter, delim, response.getOutputStream());
        }
    }

    @GetMapping
    @Operation(summary = "List test cases")
    @ApiResponse(
            responseCode = "200",
            description = "Test cases retrieved",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public PageResponseDto<TestCaseResponseDto> getAll(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
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
                    boolean includeTotalCount,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        int resolvedPage = paginationParamResolver.resolvePage(page);
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return testCaseService.getAll(
                datasetId, resolvedPage, resolvedSize, sort, filter, includeTotalCount, includeWarnings);
    }

    @GetMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    @Operation(summary = "Get test case by ID")
    @ApiResponse(
            responseCode = "200",
            description = "Test case found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestCaseResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Dataset or test case not found")
    public TestCaseResponseDto getById(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Test case ID") @PathVariable UUID id,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.getById(datasetId, id, includeWarnings);
    }

    @PutMapping
    @Operation(
            summary = "Batch full update of test cases",
            description =
                    "Atomically updates all test cases in the array. All IDs must exist in the specified dataset.",
            requestBody =
                    @RequestBody(
                            description = "Array of test case full-update items",
                            content = @Content(mediaType = "application/json")))
    @ApiResponse(
            responseCode = "200",
            description = "All test cases updated",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Empty array, exceeds max items, duplicate IDs, or invalid input")
    @ApiResponse(responseCode = "404", description = "Dataset or test case not found")
    @ApiResponse(
            responseCode = "409",
            description = "Name uniqueness violation within batch or with existing test cases")
    public List<TestCaseResponseDto> batchUpdate(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Valid @org.springframework.web.bind.annotation.RequestBody List<@Valid TestCaseBatchPutItemDto> items,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.batchUpdate(datasetId, items, includeWarnings);
    }

    @PatchMapping
    @Operation(
            summary = "Batch partial update of test cases (RFC 7396 JSON Merge Patch)",
            description = "Atomically applies merge-patch to all items. Each item must contain an 'id' field.",
            requestBody =
                    @RequestBody(
                            description = "Array of merge-patch items, each with mandatory 'id'",
                            content = @Content(mediaType = "application/json")))
    @ApiResponse(
            responseCode = "200",
            description = "All test cases updated",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(
            responseCode = "400",
            description = "Empty array, exceeds max items, duplicate IDs, missing/invalid id")
    @ApiResponse(responseCode = "404", description = "Dataset or test case not found")
    @ApiResponse(
            responseCode = "409",
            description = "Name uniqueness violation within batch or with existing test cases")
    public List<TestCaseResponseDto> batchPatch(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @org.springframework.web.bind.annotation.RequestBody List<Map<String, Object>> items,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.batchPatch(datasetId, items != null ? items : List.of(), includeWarnings);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Full update of a test case",
            requestBody =
                    @RequestBody(
                            description = "Updated test case",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestCaseRequestDto.class))))
    @ApiResponse(
            responseCode = "200",
            description = "Test case updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestCaseResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Dataset or test case not found")
    public TestCaseResponseDto update(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Test case ID") @PathVariable UUID id,
            @Valid @org.springframework.web.bind.annotation.RequestBody TestCaseRequestDto dto,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.update(datasetId, id, dto, includeWarnings);
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Partial update (RFC 7396 JSON Merge Patch)",
            requestBody =
                    @RequestBody(
                            description = "JSON Merge Patch body",
                            content = @Content(mediaType = "application/json")))
    @ApiResponse(
            responseCode = "200",
            description = "Test case updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestCaseResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Dataset or test case not found")
    public TestCaseResponseDto patch(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Test case ID") @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> patchBody,
            @Parameter(description = "When true, includes validationWarnings in the response. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.patch(datasetId, id, patchBody != null ? patchBody : Map.of(), includeWarnings);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a test case")
    @ApiResponse(responseCode = "204", description = "Test case deleted")
    @ApiResponse(responseCode = "404", description = "Dataset or test case not found")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Test case ID") @PathVariable UUID id) {
        testCaseService.delete(datasetId, id);
    }

    @DeleteMapping
    @Operation(summary = "Bulk delete test cases (optional filter)")
    @ApiResponse(responseCode = "200", description = "Returns count of deleted test cases")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public BulkDeleteResponse deleteAll(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter) {
        long deleted = testCaseService.deleteAll(datasetId, filter);
        return new BulkDeleteResponse(deleted);
    }

    public record BulkDeleteResponse(long deleted) {}
}
