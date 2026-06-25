package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreResultResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/analytics/metric-score-results")
@RequiredArgsConstructor
@Tag(name = "Metric Score Results", description = "Computed metric-score statistics per run")
public class MetricScoreResultController {

    private final MetricScoreService metricScoreService;

    @GetMapping
    @Operation(summary = "List computed metric-score results for a run and computation")
    public List<MetricScoreResultResponseDto> list(
            @Parameter(description = "Test suite run id", required = true) @RequestParam UUID testSuiteRunId,
            @Parameter(description = "Computation id or 'latest' (default)")
                    @RequestParam(required = false, defaultValue = "latest")
                    String computation) {
        return metricScoreService.listResults(testSuiteRunId, computation);
    }
}
