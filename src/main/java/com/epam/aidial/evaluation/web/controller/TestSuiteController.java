package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.TestSuiteCloneService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDetachRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteDeleteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/test-suites")
@RequiredArgsConstructor
@Tag(name = "Test Suites", description = "Test Suite management endpoints")
public class TestSuiteController {

    private final TestSuiteService testSuiteService;
    private final TestSuiteCloneService testSuiteCloneService;
    private final PaginationParamResolver paginationParamResolver;

    @GetMapping
    @Operation(summary = "Get all test suites", description = "Retrieves all test suites with pagination")
    @ApiResponse(
            responseCode = "200",
            description = "Test suites retrieved successfully",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    public PageResponseDto<TestSuiteResponseDto> getAll(
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
        return testSuiteService.getAll(resolvedPage, resolvedSize, sort, filter, includeTotalCount);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a test suite by ID", description = "Retrieves a test suite by its unique identifier")
    @ApiResponse(
            responseCode = "200",
            description = "Test suite found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public ResponseEntity<TestSuiteResponseDto> getById(
            @Parameter(description = "Test suite ID") @PathVariable UUID id) {

        TestSuiteResponseDto dto = testSuiteService.getById(id);
        return ResponseEntity.ok().eTag(etag(dto.getVersion())).body(dto);
    }

    @PostMapping
    @Operation(
            summary = "Create a new test suite",
            description = "Creates a new test suite with the provided details. "
                    + "`datasetId` is optional — when omitted, the suite is created in the unbound state "
                    + "(retrievable and updatable, but cannot be run until bound to a dataset). "
                    + "Binding to a PRIVATE dataset already owned by another suite returns 409.",
            requestBody =
                    @RequestBody(
                            description = "Test suite to create",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestSuiteRequestDto.class))))
    @ApiResponse(
            responseCode = "201",
            description = "Test suite created successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(
            responseCode = "409",
            description = "Target PRIVATE dataset is already bound to another suite (PRIVATE_DATASET_ALREADY_BOUND)")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TestSuiteResponseDto> create(
            @Valid @org.springframework.web.bind.annotation.RequestBody TestSuiteRequestDto testSuiteRequestDto,
            @AuthenticationPrincipal Jwt jwt) {

        TestSuiteResponseDto dto = testSuiteService.create(testSuiteRequestDto, jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(dto.getVersion()))
                .body(dto);
    }

    @PostMapping("/{id}/clone")
    @Operation(
            summary = "Clone a test suite",
            description = """
                Creates a copy of a test suite's configuration, TSMDs, and suite-level DIAL files. \
                By default the clone shares the source's dataset (test cases are owned by the dataset, \
                not copied). When the source is bound to a PRIVATE dataset and no datasetId override is \
                given, the dataset is also cloned — a new PRIVATE dataset with copied test cases (new ids) \
                and dataset-scoped files — and the clone is bound to it. For a PUBLIC or unbound source, \
                supplying datasetId rebinds the clone to that dataset without cloning. When the source is \
                bound to a PRIVATE dataset the clone cannot be redirected: datasetId must be omitted or equal \
                to the source's dataset id (both clone the PRIVATE dataset); a different datasetId returns 409 \
                (PRIVATE_DATASET_REBIND_FORBIDDEN). \
                Override fields are applied to the clone; null fields inherit from the source. Suite-level \
                validation runs synchronously during the clone; no async revalidation is spawned, so the \
                response's revalidationTask is always null. Returns 201.""",
            requestBody =
                    @RequestBody(
                            description = "Clone request with required name and optional overrides",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestSuiteCloneRequestDto.class))))
    @ApiResponse(
            responseCode = "201",
            description = "Test suite cloned successfully (synchronous validation only; revalidationTask is null)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteUpdateResultDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Source test suite or referenced dataset not found")
    @ApiResponse(
            responseCode = "409",
            description = "Test suite name already exists, datasetId redirects a PRIVATE-dataset clone to a "
                    + "different dataset (PRIVATE_DATASET_REBIND_FORBIDDEN), or the datasetId override is a "
                    + "PRIVATE dataset already bound to another suite (PRIVATE_DATASET_ALREADY_BOUND)")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TestSuiteUpdateResultDto> clone(
            @Parameter(description = "Source test suite ID") @PathVariable UUID id,
            @Valid @org.springframework.web.bind.annotation.RequestBody TestSuiteCloneRequestDto cloneRequestDto,
            @AuthenticationPrincipal Jwt jwt) {

        TestSuiteUpdateResultDto result = testSuiteCloneService.clone(id, cloneRequestDto, jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{id}/detach-dataset")
    @Operation(
            summary = "Detach suite from its PUBLIC dataset",
            description = """
                Forks the suite's bound PUBLIC dataset into a new PRIVATE clone and rebinds the suite \
                to the clone in a single atomic operation. The original PUBLIC dataset is left untouched. \
                Test cases are copied with fresh IDs. An optional `name` field sets the clone name; if \
                omitted, the name is derived as "<source> (clone)". Returns 409 when the suite has no \
                dataset bound, or when the bound dataset is already PRIVATE. Returns 200 with the \
                updated TestSuiteResponseDto.""",
            requestBody =
                    @RequestBody(
                            description = "Optional clone name; omit to derive automatically",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DatasetDetachRequestDto.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "minimal",
                                                        summary = "Derive name automatically",
                                                        value = "{}"),
                                                @ExampleObject(
                                                        name = "with-name",
                                                        summary = "Provide explicit clone name",
                                                        value = "{\"name\": \"My Private Dataset\"}")
                                            })))
    @ApiResponse(
            responseCode = "200",
            description = "Dataset detached — suite is now bound to a new PRIVATE clone",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    @ApiResponse(
            responseCode = "409",
            description = "Suite has no bound dataset (SUITE_HAS_NO_DATASET), or bound dataset is already PRIVATE "
                    + "(PRIVATE_DATASET_REBIND_FORBIDDEN)")
    public ResponseEntity<TestSuiteResponseDto> detachDataset(
            @Parameter(description = "Test suite ID") @PathVariable UUID id,
            @Valid @org.springframework.web.bind.annotation.RequestBody DatasetDetachRequestDto requestDto,
            @AuthenticationPrincipal Jwt jwt) {

        TestSuiteResponseDto result = testSuiteService.detachDataset(id, requestDto, jwt);
        return ResponseEntity.ok().eTag(etag(result.getVersion())).body(result);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update a test suite",
            description =
                    "Updates an existing test suite. Requires If-Match header with current version for optimistic locking. "
                            + "Schema lives on the dataset (see /api/v1/datasets/{id}); suite updates run synchronous validation only. "
                            + "Rebinding to a different dataset or unbinding (`datasetId=null`) is forbidden when the current "
                            + "dataset is PRIVATE — delete the suite or change the dataset's visibility first.",
            requestBody =
                    @RequestBody(
                            description = "Updated test suite",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestSuiteRequestDto.class))))
    @ApiResponse(
            responseCode = "200",
            description = "Test suite updated successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestSuiteResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Test suite or referenced dataset not found")
    @ApiResponse(
            responseCode = "409",
            description = "Version conflict (stale ETag), rebind from PRIVATE forbidden "
                    + "(PRIVATE_DATASET_REBIND_FORBIDDEN), or target PRIVATE dataset already bound "
                    + "(PRIVATE_DATASET_ALREADY_BOUND)")
    public ResponseEntity<TestSuiteResponseDto> update(
            @Parameter(description = "Test suite ID") @PathVariable UUID id,
            @Parameter(description = "Current version (from ETag) for optimistic locking")
                    @RequestHeader(value = "If-Match", required = true)
                    String ifMatch,
            @Valid @org.springframework.web.bind.annotation.RequestBody TestSuiteRequestDto testSuiteRequestDto) {

        Long expectedVersion = parseVersion(ifMatch);
        TestSuiteResponseDto suite = testSuiteService.update(id, testSuiteRequestDto, expectedVersion);
        return ResponseEntity.ok().eTag(etag(suite.getVersion())).body(suite);
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a test suite",
            description = "Deletes a test suite. Test cases live on the dataset and are not affected.")
    @ApiResponse(responseCode = "200", description = "Test suite deleted successfully")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public ResponseEntity<TestSuiteDeleteResponseDto> delete(
            @Parameter(description = "Test suite ID") @PathVariable UUID id) {

        TestSuiteDeleteResponseDto result = testSuiteService.delete(id);
        return ResponseEntity.ok(result);
    }

    private static String etag(Long version) {
        if (version == null) {
            return null;
        }
        return "\"" + version + "\"";
    }

    private static Long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ValidationException("If-Match header is required for update");
        }
        String trimmed = ifMatch.trim().replaceAll("^\"|\"$", "");
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new ValidationException("If-Match must be a version number (or quoted version), got: " + ifMatch);
        }
    }
}
