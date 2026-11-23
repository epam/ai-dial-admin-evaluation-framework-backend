package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.CostService;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalCostRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalCostResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalRunCostRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalRunCostResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.path.WildcardPathResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/costs")
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Costs", description = "Cost analytics endpoints backed by dial-adas usage logs")
public class CostController {

    private final CostService costService;
    private final WildcardPathResolver wildcardPathResolver;

    @GetMapping("/deployment/**")
    @Operation(
            summary = "Get total test-case and metric-evaluation cost for a deployment over a time range",
            description = "Queries dial-adas usage logs filtered by deployment id and [from, to] (inclusive, "
                    + "epoch milliseconds) and returns the total price of test-case execution calls and "
                    + "metric-evaluation (judge model) calls. Everything after the 'deployment' segment is the "
                    + "deployment ID, so IDs containing slashes are supported as-is "
                    + "(e.g. /api/v1/costs/deployment/applications/public/my-app__0.0.1). The ID is "
                    + "matched as sent, still percent-encoded (e.g. a space as %20), because the usage log "
                    + "stores deployment IDs in that form. To query a specific "
                    + "window (e.g. this month, previous month) compute its epoch-millisecond bounds "
                    + "client-side and pass them as from/to. A phase with no matching usage-log rows returns "
                    + "null for that total.")
    @ApiResponse(responseCode = "200", description = "Costs computed")
    @ApiResponse(responseCode = "400", description = "Empty deployment ID or from > to")
    @ApiResponse(responseCode = "502", description = "dial-adas unreachable or returned an error")
    @ApiResponse(responseCode = "504", description = "dial-adas request timed out")
    public DeploymentCostsResponseDto getDeploymentCosts(
            HttpServletRequest request,
            @Parameter(description = "Range start, inclusive (epoch milliseconds)", required = true) @RequestParam
                    Long from,
            @Parameter(description = "Range end, inclusive (epoch milliseconds)", required = true) @RequestParam
                    Long to) {
        final String deploymentId = wildcardPathResolver.resolveTailEncoded(request);
        validateId(deploymentId);
        return costService.getDeploymentCosts(deploymentId, from, to);
    }

    @GetMapping("/test-suite-run/{id}")
    @Operation(
            summary = "Get average test-case and metric-evaluation cost for a run",
            description = "Queries dial-adas usage logs for the run and returns the average per-call price "
                    + "for test-case execution calls and metric-evaluation (judge model) calls. A phase with "
                    + "no matching usage-log rows returns null for that average. This is the canonical "
                    + "cost-API route for run costs; `GET /api/v1/test-suite-runs/{id}/costs` is kept as a "
                    + "backward-compatible alias backed by the same computation.")
    @ApiResponse(responseCode = "200", description = "Costs computed")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "502", description = "dial-adas unreachable or returned an error")
    @ApiResponse(responseCode = "504", description = "dial-adas request timed out")
    public RunCostsResponseDto getRunCosts(@Parameter(description = "Run ID") @PathVariable UUID id) {
        return costService.getRunCosts(id);
    }

    @PostMapping(
            value = "/test-suite-runs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get total cost for a batch of runs in one call",
            description = "Computes total cost and its test-case execution and metric-evaluation phase split "
                    + "for up to " + ValidationConstants.MAX_BATCH_RUN_IDS + " runs in a single dial-adas call, "
                    + "returned in the order the run ids were given. All cost fields are null when dial-adas has no "
                    + "matching usage-log rows for that run id — including an unknown/nonexistent run id, which "
                    + "is indistinguishable from a real run with no usage. Unlike `GET "
                    + "/api/v1/costs/test-suite-run/{id}`, run ids are not validated against existing runs. "
                    + "POST (with the run ids in the body) rather than GET, since a page's worth of run ids "
                    + "would otherwise make for an unwieldy query string. If the underlying dial-adas call "
                    + "itself fails, the whole request fails with 502/504 — there is no per-run partial result, "
                    + "since one call computes the entire batch.")
    @RequestBody(
            description = "Run ids to compute total cost for",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TotalRunCostRequestDto.class),
                            examples = {
                                @ExampleObject(
                                        name = "minimal",
                                        summary = "Single run id",
                                        value = "{\"runIds\":[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]}"),
                                @ExampleObject(
                                        name = "full",
                                        summary = "Multiple run ids",
                                        value = "{\"runIds\":[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                                                + "\"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d\"]}")
                            }))
    @ApiResponse(
            responseCode = "200",
            description = "One entry per requested run id, in the requested order",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = TotalRunCostResponseDto.class)),
                            examples = {
                                @ExampleObject(
                                        name = "minimal",
                                        summary = "One run with phase totals",
                                        value = "[{\"runId\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                                                + "\"totalCost\":0.0007125,\"testCaseCost\":0.0005,"
                                                + "\"metricEvalCost\":0.0002125}]"),
                                @ExampleObject(
                                        name = "full",
                                        summary = "A run with costs and a run without usage data",
                                        value = "[{\"runId\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                                                + "\"totalCost\":0.0007125,\"testCaseCost\":0.0005,"
                                                + "\"metricEvalCost\":0.0002125},{\"runId\":"
                                                + "\"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d\",\"totalCost\":null,"
                                                + "\"testCaseCost\":null,\"metricEvalCost\":null}]"),
                            }))
    @ApiResponse(
            responseCode = "400",
            description = "`runIds` is empty, absent, or exceeds " + ValidationConstants.MAX_BATCH_RUN_IDS + " entries")
    @ApiResponse(responseCode = "502", description = "dial-adas unreachable or returned an error")
    @ApiResponse(responseCode = "504", description = "dial-adas request timed out")
    public List<TotalRunCostResponseDto> getTotalRunCosts(
            @Valid @org.springframework.web.bind.annotation.RequestBody TotalRunCostRequestDto request) {
        return costService.getTotalRunCosts(request.getRunIds());
    }

    @PostMapping(
            value = "/total",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get total evaluation cost for a period, optionally filtered by deployments",
            description = "Computes, in a single dial-adas call, the total cost of all evaluation traffic "
                    + "(test-case execution plus metric-evaluation judge calls) in [from, to] (inclusive, epoch "
                    + "milliseconds), summing the cost of dial-adas usage-log rows tagged with the "
                    + "evaluation phase, with its execution and metric-evaluation split. Without `deploymentIds` "
                    + "the request's own price (`deployment_price`) is summed, so a request nested under an "
                    + "application is counted once. With `deploymentIds` (up to "
                    + ValidationConstants.MAX_COST_DEPLOYMENT_IDS
                    + " entries) the totals are restricted to those deployments and `total_price` (the request "
                    + "plus everything it initiated) is summed, so applications report their cost; listing an "
                    + "application together with a deployment it calls counts that shared cost twice. Omitted, "
                    + "null or empty `deploymentIds` means all deployments. Ids are matched as sent, still "
                    + "percent-encoded, because the usage log "
                    + "stores deployment IDs in that form. All three costs are null when no usage-log row "
                    + "matches; a phase with no usage inside a non-empty period is 0. Usage of runs that were "
                    + "deleted is still counted, since usage logs are not tombstoned. POST (with the "
                    + "parameters in the body) rather than GET, since a long deployment id list would make for "
                    + "an unwieldy query string.")
    @ApiResponse(
            responseCode = "200",
            description = "Costs computed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TotalCostResponseDto.class),
                            examples = {
                                @ExampleObject(
                                        name = "minimal",
                                        summary = "Total with phase split",
                                        value = "{\"totalCost\":0.0123,\"testCaseCost\":0.0100,"
                                                + "\"metricEvalCost\":0.0023}"),
                                @ExampleObject(
                                        name = "no-usage",
                                        summary = "Period without any evaluation usage",
                                        value = "{\"totalCost\":null,\"testCaseCost\":null,"
                                                + "\"metricEvalCost\":null}")
                            }))
    @ApiResponse(
            responseCode = "400",
            description = "`from`/`to` missing, from > to, `deploymentIds` over "
                    + ValidationConstants.MAX_COST_DEPLOYMENT_IDS + " entries or containing a blank id")
    @ApiResponse(responseCode = "502", description = "dial-adas unreachable or returned an error")
    @ApiResponse(responseCode = "504", description = "dial-adas request timed out")
    public TotalCostResponseDto getTotalCost(
            @Valid @org.springframework.web.bind.annotation.RequestBody TotalCostRequestDto request) {
        return costService.getTotalCosts(request.getFrom(), request.getTo(), request.getDeploymentIds());
    }

    private static void validateId(String deploymentId) {
        if (StringUtils.isBlank(deploymentId)) {
            throw new ValidationException("Deployment ID must not be empty");
        }
    }
}
