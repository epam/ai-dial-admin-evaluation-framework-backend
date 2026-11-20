package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Week by week counts of healthy and failing test suites")
public class SuiteHealthDistributionDto {

    @Schema(description = "Pass-rate threshold applied (0 to 1)", example = "0.75")
    private double threshold;

    @Schema(description = "One entry per requested week, oldest first; the last is the current week")
    private List<SuiteHealthWeekDto> weeks;
}
