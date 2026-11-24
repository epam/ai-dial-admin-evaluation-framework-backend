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
@Schema(description = "Healthy and failing suite counts of one UTC week")
public class SuiteHealthWeekDto {

    @Schema(
            description = "First millisecond of the week (Monday 00:00:00.000 UTC), epoch ms",
            example = "1794787200000")
    private long weekStart;

    @Schema(description = "Suites whose weekly pass rate is at or above the threshold", example = "12")
    private int healthyCount;

    @Schema(description = "Suites whose weekly pass rate is below the threshold", example = "3")
    private int failingCount;

    @Schema(description = "healthyCount plus failingCount", example = "15")
    private int suiteCount;
}
