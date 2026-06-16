package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TryItOutService;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutWithVariablesRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@RequestMapping("/api/v1/test-suites/{testSuiteId}")
@RequiredArgsConstructor
@Tag(name = "Try It Out", description = "Send resolved requests to DIAL Core deployments")
public class TestSuiteTryOutController {

    private final TryItOutService tryItOutService;

    @PostMapping("/try-it-out")
    @Operation(
            summary = "Try it out with variables",
            description =
                    "Resolves the suite's request template using the provided variables as constant-value bindings, "
                            + "sends the resolved request to the DIAL Core deployment, and returns the response.")
    @ApiResponse(
            responseCode = "200",
            description = "Try-it-out completed successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TryItOutResponseDto.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Validation error (missing deployment/template/endpoint, null variables, or unresolved variables)")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    @ApiResponse(responseCode = "502", description = "DIAL Core unreachable")
    @ApiResponse(responseCode = "504", description = "DIAL Core timeout")
    public TryItOutResponseDto tryWithVariables(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Valid @RequestBody TryItOutWithVariablesRequestDto request) {
        return tryItOutService.tryWithVariables(testSuiteId, request.getVariables());
    }
}
