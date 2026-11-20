package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.SuiteHealthDistributionService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteHealthDistributionDto;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/test-suites")
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Suite Health Distribution", description = "Weekly counts of healthy and failing test suites")
public class SuiteHealthDistributionController {

    private final SuiteHealthDistributionService suiteHealthDistributionService;

    @GetMapping(value = "/health-distribution", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Weekly counts of healthy and failing test suites",
            description = "One entry per week, oldest first, the last being the current week (weeks start on Monday"
                    + " 00:00:00.000 UTC). A suite's weekly pass rate pools the passed and total test case counts of"
                    + " its completed runs created in that week, each at its latest computation; a suite is counted"
                    + " in a week only when it has runs with test cases in it, and is healthy when its weekly rate"
                    + " is at or above `threshold`. Deleted runs are excluded. Weeks without counted suites are"
                    + " present with all counts 0. Not paged; there is no `sort` parameter. Counts come from"
                    + " recorded run pass counts, so older weeks can be under-reported until reconciliation has"
                    + " recorded the older runs (first pass about a minute after startup).")
    @ApiResponse(
            responseCode = "200",
            description = "Weekly healthy and failing suite counts",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SuiteHealthDistributionDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid `weeks`, `threshold` or `filter`")
    public SuiteHealthDistributionDto getHealthDistribution(
            @Parameter(
                            description = "Number of weeks in the response `weeks` list, the current week included"
                                    + " (1 to 52, default 8)")
                    @RequestParam(required = false)
                    @Min(1)
                    @Max(AnalyticsConstants.HEALTH_DISTRIBUTION_MAX_WEEKS)
                    Integer weeks,
            @Parameter(
                            description = "Pass-rate threshold from 0 to 1 inclusive (default 0.75); a suite is healthy"
                                    + " when its weekly pass rate is greater than or equal to it")
                    @RequestParam(required = false)
                    @DecimalMin("0")
                    @DecimalMax("1")
                    Double threshold,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter) {
        return suiteHealthDistributionService.getDistribution(
                weeks != null ? weeks : AnalyticsConstants.HEALTH_DISTRIBUTION_DEFAULT_WEEKS,
                threshold != null ? threshold : AnalyticsConstants.HEALTH_DISTRIBUTION_DEFAULT_THRESHOLD,
                filter);
    }
}
