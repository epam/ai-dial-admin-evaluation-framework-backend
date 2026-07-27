package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TryItOutService;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@RequestMapping("/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}")
@RequiredArgsConstructor
@Tag(name = "Try It Out", description = "Send resolved requests to DIAL Core deployments")
public class TestCaseTryOutController {

    private final TryItOutService tryItOutService;

    @PostMapping("/try-it-out")
    @Operation(
            summary = "Try it out with test case data",
            description = "Resolves the effective request template using the test case's data and bindings, "
                    + "sends the resolved request to the DIAL Core deployment, and returns the response. "
                    + "For a multi-request suite, `requestIndex` selects which chain request to instantiate; only "
                    + "that one request is sent. A `responseField` binding has no producing request here, so it "
                    + "falls back to its placeholder default when declared and otherwise yields an HTTP 200 with a "
                    + "`resolvedRequest.warnings` entry naming the unresolved variable.")
    @ApiResponse(
            responseCode = "200",
            description = "Try-it-out completed successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TryItOutResponseDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation error (missing deployment/template/endpoint or unresolved variables)")
    @ApiResponse(responseCode = "404", description = "Test suite or test case not found")
    @ApiResponse(responseCode = "502", description = "DIAL Core unreachable")
    @ApiResponse(responseCode = "504", description = "DIAL Core timeout")
    public TryItOutResponseDto tryWithTestCase(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = "Test case ID") @PathVariable UUID testCaseId,
            @Parameter(
                            description = "Which chain request of a multi-request suite to instantiate, "
                                    + "0-based. Defaults to 0 — the suite's flat configuration. Try-out remains a "
                                    + "single-endpoint operation: ONLY the selected request is sent, no preceding "
                                    + "chain request is executed. The index rather than the label is the selector "
                                    + "because the index is a result-row natural-key component.",
                            example = "0")
                    @RequestParam(name = "requestIndex", required = false)
                    Integer requestIndex) {
        return tryItOutService.tryWithTestCase(testSuiteId, testCaseId, requestIndex);
    }
}
