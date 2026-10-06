package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TemplateVariableService;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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

    private static final String RESPONSE_SHAPE = "Response is an object keyed by request index (JSON string keys): "
            + "\"0\" is the suite's own request, \"n\" is additionalRequests[n-1]. "
            + "Every request index is present, in chain order; a request without variables maps to an empty array.";

    private final TemplateVariableService templateVariableService;

    @GetMapping(value = "/{testSuiteId}/template-variables", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get template variables for a test suite",
            description = "Extracts all template variables from the suite's requestTemplate and from every "
                    + "additionalRequests[i].requestTemplate, each resolved against its own bindings and endpoint, "
                    + "and infers types (priority: endpointRef schema > dataset testCaseSchema > STRING). "
                    + RESPONSE_SHAPE)
    @ApiResponse(responseCode = "200", description = "Template variables retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public Map<Integer, List<TemplateVariableDto>> getTemplateVariables(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId) {
        return templateVariableService.getTemplateVariables(testSuiteId);
    }

    @GetMapping(
            value = "/{testSuiteId}/test-cases/{testCaseId}/template-variables",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get effective template variables for a test case",
            description =
                    "Returns template variables for every request of the suite (the suite's own requestTemplate and "
                            + "each additionalRequests[i].requestTemplate), with bindings resolved against the "
                            + "specified test case's data. Per-test-case template/binding overrides were removed when test "
                            + "cases moved to datasets, so the suite is the single source of truth; the only difference from "
                            + "the suite-level endpoint is that resolvedValue reflects the test case's data. "
                            + RESPONSE_SHAPE)
    @ApiResponse(responseCode = "200", description = "Template variables retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Test suite or test case not found")
    public Map<Integer, List<TemplateVariableDto>> getTestCaseTemplateVariables(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Test case ID") @PathVariable UUID testCaseId) {
        return templateVariableService.getTestCaseTemplateVariables(testSuiteId, testCaseId);
    }
}
