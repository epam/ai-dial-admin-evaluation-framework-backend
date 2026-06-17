package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestCaseService;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteResponseDto;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// Dedicated class required: Spring appends '/' between class- and method-level mappings, so /test-cases:bulk cannot
// live inside TestCaseController.
@Slf4j
@RestController
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Test Cases", description = "TestCase CRUD and PATCH endpoints")
public class TestCaseBulkDeleteController {

    private final TestCaseService testCaseService;

    @DeleteMapping("/api/v1/datasets/{datasetId}/test-cases:bulk")
    @Operation(
            summary = "Bulk delete test cases by explicit UUID list",
            description =
                    "Deletes all test cases whose IDs are in the request body and belong to the specified dataset. "
                            + "Uses partial-success semantics: IDs found in the dataset are deleted; absent IDs are reported "
                            + "in `notFound` without aborting the operation. Both response lists preserve input ordering.",
            requestBody =
                    @RequestBody(
                            description = "List of test case UUIDs to delete",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TestCaseBulkDeleteRequestDto.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "delete-two-ids",
                                                        description = "Delete two test cases by ID.",
                                                        value = "{\"ids\":[\"11111111-1111-1111-1111-111111111111\","
                                                                + "\"22222222-2222-2222-2222-222222222222\"]}")
                                            })))
    @ApiResponse(
            responseCode = "200",
            description = "Operation applied with partial-success semantics. "
                    + "`deleted` contains IDs removed; `notFound` contains IDs absent from the dataset.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TestCaseBulkDeleteResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Empty ids, null element, duplicate id, or cap exceeded")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public TestCaseBulkDeleteResponseDto bulkDelete(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Valid @org.springframework.web.bind.annotation.RequestBody TestCaseBulkDeleteRequestDto request) {
        return testCaseService.bulkDelete(datasetId, request);
    }
}
