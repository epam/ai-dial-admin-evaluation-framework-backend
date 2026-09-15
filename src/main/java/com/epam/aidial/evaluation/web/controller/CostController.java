package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.CostService;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Costs", description = "Cost analytics endpoints backed by dial-adas usage logs")
public class CostController {

    private final CostService costService;

    @GetMapping("/api/v1/costs")
    @Operation(
            summary = "Get total test-case and metric-evaluation cost for a deployment over a time range",
            description = "Queries dial-adas usage logs filtered by deployment id and [from, to] (inclusive, "
                    + "epoch milliseconds) and returns the total price of test-case execution calls and "
                    + "metric-evaluation (judge model) calls. To query a specific window (e.g. this month, "
                    + "previous month) compute its epoch-millisecond bounds client-side and pass them as "
                    + "from/to. A phase with no matching usage-log rows returns null for that total.")
    @ApiResponse(responseCode = "200", description = "Costs computed")
    @ApiResponse(responseCode = "400", description = "Missing/blank deploymentId, or from > to")
    @ApiResponse(responseCode = "502", description = "dial-adas unreachable or returned an error")
    @ApiResponse(responseCode = "504", description = "dial-adas request timed out")
    public DeploymentCostsResponseDto getDeploymentCosts(
            @Parameter(description = "Deployment ID", required = true) @RequestParam @NotBlank String deploymentId,
            @Parameter(description = "Range start, inclusive (epoch milliseconds)", required = true) @RequestParam
                    Long from,
            @Parameter(description = "Range end, inclusive (epoch milliseconds)", required = true) @RequestParam
                    Long to) {
        return costService.getDeploymentCosts(deploymentId, from, to);
    }
}
