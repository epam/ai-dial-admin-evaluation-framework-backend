package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.ResolvedRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}")
@RequiredArgsConstructor
@Tag(name = "Resolved Request", description = "Preview resolved request after applying template, bindings, and data")
public class ResolvedRequestController {

    private final ResolvedRequestService resolvedRequestService;

    @GetMapping("/resolved-request")
    @Operation(
            summary = "Get resolved request preview for a test case",
            description = "Resolves the effective template with effective bindings and test case data. "
                    + "Returns the assembled URL, query params, headers, and body with any resolution warnings.")
    @ApiResponse(
            responseCode = "200",
            description = "Resolved request retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ResolvedRequestDto.class)))
    @ApiResponse(responseCode = "404", description = "Test suite or test case not found")
    public ResolvedRequestDto getResolvedRequest(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Test case ID") @PathVariable UUID testCaseId) {
        return resolvedRequestService.resolveRequest(testSuiteId, testCaseId);
    }
}
