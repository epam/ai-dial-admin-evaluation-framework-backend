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
            description = "For each suite, takes its latest two recorded completed runs, each at its latest"
                    + " computation, and computes the run's pass rate from the stored pass counts: the share of test"
                    + " cases that passed. A test case passes when every one of its executions succeeded and, if the"
                    + " run has a score threshold, it was not scored below it; a failed test case or one with a"
                    + " metric error does not pass, even without a score row. Returns the mean of the suites'"
                    + " latest-run rates (`passRate`) and of their previous-run rates (`previousPassRate`), each"
                    + " suite counting once, with the number of suites behind each (`suiteCount`,"
                    + " `previousSuiteCount`). A rate with no contributing suite is omitted. Suites that do not exist"
                    + " or have no recorded completed run are skipped without an error; a completed run is ignored"
                    + " until its pass counts are recorded. The caller derives the trend from the two numbers.")
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
