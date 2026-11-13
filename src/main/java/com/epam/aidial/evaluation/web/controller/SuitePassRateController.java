package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.SuitePassRateService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/test-suites")
@LogExecution
@Validated
@RequiredArgsConstructor
@Tag(name = "Suite Pass Rate", description = "Pass rate of test suites' latest and previous runs")
public class SuitePassRateController {

    private final SuitePassRateService suitePassRateService;

    @PostMapping(value = "/pass-rate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get the mean pass rate of the latest runs and of the previous runs of a set of suites",
            description = "For each suite, takes its latest two runs that have per-test-case scores, each at its "
                    + "latest computation, and computes the run's pass rate: with a score threshold on the run, "
                    + "the share of test cases whose score met it (unscored and failed test cases do not count); "
                    + "without a threshold, the share of test cases that executed successfully. Returns the mean "
                    + "of the suites' latest-run rates (`passRate`) and of their previous-run rates "
                    + "(`previousPassRate`), each suite counting once, with the number of suites behind each "
                    + "(`suiteCount`, `previousSuiteCount`). A rate with no contributing suite is omitted. Suites "
                    + "that do not exist or have no scored run are skipped without an error. The caller derives "
                    + "the trend from the two numbers.")
    @ApiResponse(
            responseCode = "200",
            description = "Summary computed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SuitePassRateSummaryDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "`suiteIds` is empty, absent, or has more than "
                    + ValidationConstants.MAX_BATCH_SUITE_IDS
                    + " distinct entries")
    public SuitePassRateSummaryDto getPassRateSummary(@RequestBody SuitePassRateRequestDto request) {
        return suitePassRateService.summarize(request.getSuiteIds());
    }
}
