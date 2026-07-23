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
    private int turnIndex;
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
