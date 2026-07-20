package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSummaryResponseDto {

    private UUID id;
    private UUID testSuiteId;
    private UUID testSuiteRunId;
    private UUID testCaseRunResultId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;

    @Schema(
            description = "0-based multi-turn turn index this summary belongs to. Single-turn results are 0.",
            example = "0")
    private int turnIndex;

    @Schema(
            description = "Total number of turns in the multi-turn. Single-turn results are 1; "
                    + "the last turn of a multi-turn is the row where turnIndex == totalTurns - 1.",
            example = "1")
    private int totalTurns;

    private UUID computationId;
    private JsonNode testCaseData;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode extractedColumns;

    private String executionStatus;
    private Long execDurationMs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer responseStatusCode;

    private JsonNode metricValues;
    private Long createdAt;
    private Long computedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Grafana Explore URL for all traces of this test case in the run "
                    + "(present only when Grafana integration is configured)",
            example = "http://grafana:3000/explore?...")
    private String grafanaTraceUrl;
}
