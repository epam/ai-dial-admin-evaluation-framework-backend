package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.runner.model.SuiteType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A test suite with the pass rates of its latest completed runs and the change of the latest")
public class SuiteRunTrendDto {

    @Schema(description = "Suite id", example = "9d1c7a40-6b2e-4f3a-8e55-0a7b3c9d2e11")
    private UUID suiteId;

    @Schema(description = "Suite name as it is now", example = "Docs search regression")
    private String suiteName;

    @Schema(description = "Suite type as it is now", example = "MCP_TOOL")
    private SuiteType suiteType;

    @Schema(description = "What the suite evaluates")
    private EvaluateeDto evaluatee;

    @Schema(description = "Latest completed runs (at most 10), oldest first")
    private List<RunTrendPointDto> runs;

    @Schema(
            description = "Pass rate of the newest run (0 to 1); absent when that run has no test cases",
            example = "0.8")
    private Double latestPassRate;

    @Schema(description = "Pass rate of the run before the newest; absent when there is none", example = "0.9")
    private Double previousPassRate;

    @Schema(description = "latestPassRate minus previousPassRate; absent when either is absent", example = "-0.1")
    private Double delta;
}
