package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestCaseService;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dedicated controller for the composite bulk-patch endpoint.
 *
 * <p>The path uses Google API-style colon-segment ({@code :bulk}) attached directly to the
 * {@code /test-cases} resource. Spring concatenates class- and method-level paths with a {@code /}
 * separator when the method path does not start with {@code /}, so {@code /test-cases:bulk}
 * cannot be expressed as a method path on the parent {@link TestCaseController}. A dedicated
 * controller class with the full path on its method-level mapping avoids that issue.
 */
@Slf4j
@RestController
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Test Cases", description = "TestCase CRUD and PATCH endpoints")
public class TestCaseBulkPatchController {

    private final TestCaseService testCaseService;

    @PatchMapping("/api/v1/datasets/{datasetId}/test-cases:bulk")
    @Operation(
            summary = "Composite bulk partial update of test cases",
            description = "Atomically applies selector-scoped homogeneous operations and per-row "
                    + "heterogeneous merge-patch operations in a single transaction. Bulk operations "
                    + "are applied first (in array order); item operations follow on the already-bulk-updated state. "
                    + "Bulk patches are restricted to a code-defined whitelist (currently `{testCaseName, data}`); item patches "
                    + "follow single-row PATCH semantics. Filter selectors are resolved at the moment each op executes, "
                    + "so `bulkOperations[i+1]` sees the effects of `bulkOperations[i]`. Returns compact counts.",
            requestBody =
                    @RequestBody(
                            description = "Composite bulk-patch request",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestCaseBulkPatchRequestDto.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "bulk-rename-by-filter",
                                                        description =
                                                                "Bulk-rename test cases matching a filter, then per-row patch two of them.",
                                                        value = "{\"bulkOperations\":["
                                                                + "{\"selector\":{\"filter\":[\"testCaseName:like:smoke%\"]},"
                                                                + "\"patch\":{\"testCaseName\":\"smoke-archived\"}}],"
                                                                + "\"itemOperations\":["
                                                                + "{\"id\":\"11111111-1111-1111-1111-111111111111\","
                                                                + "\"patch\":{\"testCaseName\":\"Renamed A\"}},"
                                                                + "{\"id\":\"22222222-2222-2222-2222-222222222222\","
                                                                + "\"patch\":{\"data\":{\"prompt\":\"updated\"}}}]}"),
                                                @ExampleObject(
                                                        name = "ids-selector-patch-data",
                                                        description =
                                                                "Selector by ids with patch updating data on two specific rows.",
                                                        value = "{\"bulkOperations\":["
                                                                + "{\"selector\":{\"ids\":[\"11111111-1111-1111-1111-111111111111\","
                                                                + "\"22222222-2222-2222-2222-222222222222\"]},"
                                                                + "\"patch\":{\"data\":{\"prompt\":\"shared\"}}}],"
                                                                + "\"itemOperations\":[]}")
                                            })))
    @ApiResponse(
            responseCode = "200",
            description = "Composite operation applied; returns per-op counts.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestCaseBulkPatchResponseDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Empty body, cap exceeded, malformed selector, "
                    + "duplicate ids, unknown filter field, or whitelist violation")
    @ApiResponse(responseCode = "404", description = "Dataset not found, or id in selector / item not in dataset")
    @ApiResponse(responseCode = "409", description = "Final-state name uniqueness violation")
    public TestCaseBulkPatchResponseDto bulkPatch(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Valid @org.springframework.web.bind.annotation.RequestBody TestCaseBulkPatchRequestDto request,
            @Parameter(description = "Reserved; currently unused. Kept for forward-compat with item re-validation.")
                    @RequestParam(defaultValue = "false")
                    boolean includeWarnings) {
        return testCaseService.bulkPatch(datasetId, request, includeWarnings);
    }
}
