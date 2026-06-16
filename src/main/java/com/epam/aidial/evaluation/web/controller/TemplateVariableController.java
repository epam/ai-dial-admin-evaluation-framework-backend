package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TemplateVariableService;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/test-suites")
@RequiredArgsConstructor
@Tag(name = "Template Variables", description = "Template variable extraction and type inference")
public class TemplateVariableController {

    private final TemplateVariableService templateVariableService;

    @GetMapping("/{testSuiteId}/template-variables")
    @Operation(
            summary = "Get template variables for a test suite",
            description = "Extracts all template variables from the suite's requestTemplate, resolves bindings, "
                    + "and infers types (priority: endpointRef schema > dataset testCaseSchema > STRING).")
    @ApiResponse(
            responseCode = "200",
            description = "Template variables retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = TemplateVariableDto.class))))
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public List<TemplateVariableDto> getTemplateVariables(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId) {
        return templateVariableService.getTemplateVariables(testSuiteId);
    }

    @GetMapping("/{testSuiteId}/test-cases/{testCaseId}/template-variables")
    @Operation(
            summary = "Get effective template variables for a test case",
            description = "Returns template variables for the suite's template and bindings resolved against the "
                    + "specified test case's data. Per-test-case template/binding overrides were removed when test "
                    + "cases moved to datasets, so the suite is the single source of truth; the only difference from "
                    + "the suite-level endpoint is that resolvedValue reflects the test case's data.")
    @ApiResponse(
            responseCode = "200",
            description = "Template variables retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = TemplateVariableDto.class))))
    @ApiResponse(responseCode = "404", description = "Test suite or test case not found")
    public List<TemplateVariableDto> getTestCaseTemplateVariables(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Test case ID") @PathVariable UUID testCaseId) {
        return templateVariableService.getTestCaseTemplateVariables(testSuiteId, testCaseId);
    }
}
