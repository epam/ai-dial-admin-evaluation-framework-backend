package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.service.domain.DatasetService;
import com.epam.aidial.evaluation.service.domain.RevalidationService;
import com.epam.aidial.evaluation.service.domain.dto.DatasetCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDependentSuiteDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetPublishRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetVisibilityTransitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
@Tag(name = "Datasets", description = "Dataset management endpoints — owns test-case schema and test cases")
public class DatasetController {

    private final DatasetService datasetService;
    private final RevalidationService revalidationService;
    private final PaginationParamResolver paginationParamResolver;

    @GetMapping
    @Operation(summary = "Get all datasets", description = "Retrieves all datasets with pagination")
    @ApiResponse(
            responseCode = "200",
            description = "Datasets retrieved successfully",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    public PageResponseDto<DatasetResponseDto> getAll(
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
        return datasetService.getAll(resolvedPage, resolvedSize, sort, filter, includeTotalCount);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a dataset by ID", description = "Retrieves a dataset by its unique identifier")
    @ApiResponse(
            responseCode = "200",
            description = "Dataset found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DatasetResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public ResponseEntity<DatasetResponseDto> getById(@Parameter(description = "Dataset ID") @PathVariable UUID id) {

        DatasetResponseDto dto = datasetService.getById(id);
        return ResponseEntity.ok().eTag(etag(dto.getVersion())).body(dto);
    }

    @GetMapping("/{id}/test-suites")
    @Operation(
            summary = "List test suites depending on a dataset",
            description = "Returns the id, name, and description of every test suite bound to this dataset "
                    + "(suites whose datasetId references it). An empty array means the dataset has no "
                    + "dependent suites.")
    @ApiResponse(
            responseCode = "200",
            description = "Dependent test suites retrieved",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DatasetDependentSuiteDto.class)),
                            examples =
                                    @ExampleObject(
                                            value = "[{\"id\":\"550e8400-e29b-41d4-a716-446655440000\","
                                                    + "\"name\":\"Regression suite\","
                                                    + "\"description\":\"Nightly regression coverage\"}]")))
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public List<DatasetDependentSuiteDto> getDependentSuites(
            @Parameter(description = "Dataset ID") @PathVariable UUID id) {

        return datasetService.getDependentSuites(id);
    }

    @PostMapping
    @Operation(
            summary = "Create a new dataset",
            description = "Creates a new dataset with name, description, visibility, and test-case schema. "
                    + "Visibility is required and immutable via PUT — change visibility with "
                    + "PATCH /api/v1/datasets/{id}/visibility. "
                    + "PUBLIC datasets stand on their own (forbid `bindToSuiteId`); "
                    + "PRIVATE datasets require `bindToSuiteId` and atomically rebind the target suite.",
            requestBody =
                    @RequestBody(
                            description = "Dataset to create",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DatasetRequestDto.class))))
    @ApiResponse(
            responseCode = "201",
            description = "Dataset created successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DatasetResponseDto.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Invalid request body — includes PRIVATE_DATASET_REQUIRES_SUITE_BINDING / PUBLIC_DATASET_FORBIDS_SUITE_BINDING")
    @ApiResponse(
            responseCode = "409",
            description = "Dataset with the given name already exists, or target suite already bound to another "
                    + "PRIVATE dataset (PRIVATE_DATASET_ALREADY_BOUND / PRIVATE_DATASET_REBIND_FORBIDDEN)")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DatasetResponseDto> create(
            @Valid @org.springframework.web.bind.annotation.RequestBody DatasetRequestDto requestDto,
            @AuthenticationPrincipal Jwt jwt) {

        DatasetResponseDto dto = datasetService.create(requestDto, jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(dto.getVersion()))
                .body(dto);
    }

    @PostMapping("/{id}/clone")
    @Operation(
            summary = "Clone a dataset",
            description = "Deep-copies a dataset (row + all test cases with fresh ids and "
                    + "`@ef/datasets/{id}/` file-reference rewrites) into a new dataset. The clone inherits the "
                    + "source's visibility and is unbound to any suite. Both `name` and `description` are optional — "
                    + "an omitted `name` is auto-derived as `\"<source> (clone)\"` and an omitted `description` is "
                    + "copied verbatim from the source. An empty body is valid. Any source dataset (PUBLIC or PRIVATE) "
                    + "may be cloned; the source is never modified.",
            requestBody =
                    @RequestBody(
                            description = "Optional name and description overrides for the clone",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DatasetCloneRequestDto.class),
                                            examples = {
                                                @ExampleObject(name = "Derived name", value = "{}"),
                                                @ExampleObject(
                                                        name = "Custom name and description",
                                                        value =
                                                                "{\"name\": \"My Dataset (clone)\", \"description\": \"Clone for experimentation\"}")
                                            })))
    @ApiResponse(
            responseCode = "201",
            description = "Dataset cloned successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DatasetResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error (name or description too long)")
    @ApiResponse(responseCode = "404", description = "Source dataset not found")
    @ApiResponse(responseCode = "409", description = "A dataset with the resolved name already exists")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DatasetResponseDto> clone(
            @Parameter(description = "Source dataset ID") @PathVariable UUID id,
            @Valid @org.springframework.web.bind.annotation.RequestBody(required = false)
                    DatasetCloneRequestDto requestDto,
            @AuthenticationPrincipal Jwt jwt) {

        DatasetCloneRequestDto effective = requestDto != null ? requestDto : new DatasetCloneRequestDto();
        DatasetResponseDto dto = datasetService.clone(id, effective, jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(dto.getVersion()))
                .body(dto);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update a dataset",
            description =
                    "Updates an existing dataset. Requires If-Match header with current version for optimistic locking. "
                            + "If testCaseSchema changed, returns 202 Accepted with a revalidation task. "
                            + "The `visibility` field is silently ignored — visibility is immutable via PUT; "
                            + "use PATCH /api/v1/datasets/{id}/visibility instead.",
            requestBody =
                    @RequestBody(
                            description = "Updated dataset",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DatasetRequestDto.class))))
    @ApiResponse(
            responseCode = "200",
            description = "Dataset updated successfully (no schema change)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DatasetResponseDto.class)))
    @ApiResponse(
            responseCode = "202",
            description = "Dataset updated; async re-validation started (schema changed)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = RevalidationTaskDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    @ApiResponse(responseCode = "409", description = "Version conflict (stale ETag) or name already in use")
    public ResponseEntity<?> update(
            @Parameter(description = "Dataset ID") @PathVariable UUID id,
            @Parameter(description = "Current version (from ETag) for optimistic locking")
                    @RequestHeader(value = "If-Match", required = true)
                    String ifMatch,
            @Valid @org.springframework.web.bind.annotation.RequestBody DatasetRequestDto requestDto) {

        Long expectedVersion = parseVersion(ifMatch);
        DatasetUpdateResultDto result = datasetService.update(id, requestDto, expectedVersion);
        if (result.getRevalidationTask() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.getRevalidationTask());
        }
        return ResponseEntity.ok().eTag(etag(result.getDataset().getVersion())).body(result.getDataset());
    }

    @GetMapping("/{id}/revalidation-tasks")
    @Operation(summary = "List revalidation tasks", description = "Lists recent revalidation tasks for the dataset")
    @ApiResponse(
            responseCode = "200",
            description = "Revalidation tasks retrieved",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public PageResponseDto<RevalidationTaskDto> listRevalidationTasks(
            @Parameter(description = "Dataset ID") @PathVariable UUID id,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        int resolvedPage = paginationParamResolver.resolvePage(page);
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return PageResponseDto.from(revalidationService.listTasks(id, resolvedPage, resolvedSize), dto -> dto, true);
    }

    @GetMapping("/{id}/revalidation-tasks/{taskId}")
    @Operation(summary = "Get revalidation task", description = "Gets a revalidation task by its ID")
    @ApiResponse(
            responseCode = "200",
            description = "Revalidation task found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = RevalidationTaskDto.class)))
    @ApiResponse(responseCode = "404", description = "Dataset or task not found")
    public ResponseEntity<RevalidationTaskDto> getRevalidationTask(
            @Parameter(description = "Dataset ID") @PathVariable UUID id,
            @Parameter(description = "Revalidation task ID") @PathVariable UUID taskId) {

        return revalidationService
                .getTask(id, taskId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EntityNotFoundException("Revalidation task not found: " + taskId));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a dataset",
            description = "Deletes a dataset. Behavior depends on visibility: "
                    + "PUBLIC datasets enforce FK RESTRICT — returns 409 if any test suite still references the dataset; "
                    + "PRIVATE datasets cascade — atomically unbinds the bound suite (sets datasetId=null) and deletes "
                    + "the dataset plus its test cases in one transaction.")
    @ApiResponse(responseCode = "204", description = "Dataset deleted successfully")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    @ApiResponse(responseCode = "409", description = "PUBLIC dataset is referenced by one or more test suites")
    public ResponseEntity<Void> delete(@Parameter(description = "Dataset ID") @PathVariable UUID id) {

        datasetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/visibility")
    @Operation(
            summary = "Transition dataset visibility",
            description = "Atomically transitions the dataset's visibility between PUBLIC and PRIVATE. "
                    + "PUBLIC→PRIVATE requires exactly one suite already bound to the dataset "
                    + "(else returns 409 PRIVATE_TRANSITION_INVALID_BINDING_COUNT). "
                    + "PRIVATE→PUBLIC always succeeds. No-op (same visibility) returns the dataset unchanged.",
            requestBody =
                    @RequestBody(
                            description = "Target visibility",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DatasetVisibilityTransitionDto.class))))
    @ApiResponse(
            responseCode = "200",
            description = "Visibility transitioned successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DatasetResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    @ApiResponse(
            responseCode = "409",
            description = "PUBLIC→PRIVATE requires exactly one bound suite (PRIVATE_TRANSITION_INVALID_BINDING_COUNT)")
    public ResponseEntity<DatasetResponseDto> transitionVisibility(
            @Parameter(description = "Dataset ID") @PathVariable UUID id,
            @Valid @org.springframework.web.bind.annotation.RequestBody DatasetVisibilityTransitionDto requestDto) {

        DatasetResponseDto dto = datasetService.transitionVisibility(id, requestDto.getVisibility());
        return ResponseEntity.ok().eTag(etag(dto.getVersion())).body(dto);
    }

    @PostMapping("/{id}/publish")
    @Operation(
            summary = "Publish a dataset",
            description =
                    "Promotes a dataset to PUBLIC visibility and optionally updates its name and description "
                            + "in a single atomic operation. When the dataset is already PUBLIC and no metadata fields change, "
                            + "the call is a no-op and returns the unchanged dataset. "
                            + "Returns 409 UNIQUE_CONSTRAINT_VIOLATION if the provided name conflicts with an existing dataset.",
            requestBody =
                    @RequestBody(
                            description = "Optional name and description to set on publish",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DatasetPublishRequestDto.class))))
    @ApiResponse(
            responseCode = "200",
            description = "Dataset published (or already PUBLIC — no-op)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DatasetResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error (name or description too long)")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    @ApiResponse(responseCode = "409", description = "Duplicate name (UNIQUE_CONSTRAINT_VIOLATION)")
    public ResponseEntity<DatasetResponseDto> publish(
            @Parameter(description = "Dataset ID") @PathVariable UUID id,
            @Valid @org.springframework.web.bind.annotation.RequestBody DatasetPublishRequestDto requestDto) {

        DatasetResponseDto dto = datasetService.publish(id, requestDto);
        return ResponseEntity.ok().eTag(etag(dto.getVersion())).body(dto);
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
