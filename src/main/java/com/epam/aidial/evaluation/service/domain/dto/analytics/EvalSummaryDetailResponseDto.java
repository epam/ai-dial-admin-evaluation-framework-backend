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
public class EvalSummaryDetailResponseDto {

    private UUID id;
    private UUID testSuiteId;
    private UUID testSuiteRunId;
    private UUID testCaseRunResultId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;

    @Schema(description = "0-based turn position within a multi-turn test case (0 for single-turn).", example = "0")
    private int turnIndex;

    @Schema(description = "Total turn count of the test case (1 for single-turn).", example = "1")
    private int totalTurns;

    @Schema(
            description = "0-based chain position of the multi-request suite request that produced this row "
                    + "(0 for single-request suites).",
            example = "0")
    private int requestIndex;

    @Schema(
            description = "Resolved label of the chain request that produced this row; null for rows written "
                    + "before this capability existed or imported without one.",
            example = "invoke")
    private String requestLabel;

    private UUID computationId;
    private JsonNode testCaseData;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode extractedColumns;

    private String executionStatus;
    private Long execDurationMs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer responseStatusCode;

    private JsonNode metricValues;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode metricInfos;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode extractionWarnings;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode requestBody;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode responseBody;

    private Long createdAt;
    private Long computedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Grafana Explore URL for all traces of this test case in the run "
                    + "(present only when Grafana integration is configured)",
            example = "http://grafana:3000/explore?...")
    private String grafanaTraceUrl;
}
