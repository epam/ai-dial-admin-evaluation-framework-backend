package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Mean pass rate of the requested suites' latest runs and of their previous runs. Each suite "
                + "counts once, whatever its size. A rate with no contributing suite is omitted and its count is 0.")
public class SuitePassRateSummaryDto {

    @Schema(
            description = "Mean, over the suites that have a scored run, of each suite's latest-run pass rate "
                    + "(0 to 1). Omitted when no suite has one.",
            example = "0.75")
    private Double passRate;

    @Schema(
            description = "Mean, over the suites that have two scored runs, of each suite's previous-run pass "
                    + "rate (0 to 1). Omitted when no suite has a previous run.",
            example = "0.0")
    private Double previousPassRate;

    @Schema(description = "Number of suites behind passRate", example = "2")
    private int suiteCount;

    @Schema(description = "Number of suites behind previousPassRate", example = "1")
    private int previousSuiteCount;
}
