package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.analytics.SuiteRunTrendService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteRunTrendDto;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Suite Run Trends", description = "Pass-rate trends of test suites' latest completed runs")
public class SuiteRunTrendController {

    private final SuiteRunTrendService suiteRunTrendService;
    private final PaginationParamResolver paginationParamResolver;

    @GetMapping(value = "/run-trends", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List test suites with the pass-rate trend of their latest completed runs, worst first",
            description = "One item per test suite that has a recorded completed run, sorted across all suites by"
                    + " the pass rate of its latest run ascending (suites whose latest run has no test cases"
                    + " last), then newest latest run first, then suite id. Each item carries the suite's name,"
                    + " type and evaluatee as they are now, its latest runs (at most 10, oldest first, each at its"
                    + " latest computation, with pass rate and counts), the latest and previous pass rates and"
                    + " their delta. Deleted runs are excluded. Takes the `filter` of the test suite list; the"
                    + " order and total always cover every matching suite. There is no `sort` parameter.")
    @ApiResponse(
            responseCode = "200",
            description = "Page of suite run trends",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PageResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid `page`, `size` or `filter`")
    public PageResponseDto<SuiteRunTrendDto> getRunTrends(
            @Parameter(description = "Page number") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter) {
        return suiteRunTrendService.getTrends(
                paginationParamResolver.resolvePage(page), paginationParamResolver.resolveSize(size), filter);
    }
}
