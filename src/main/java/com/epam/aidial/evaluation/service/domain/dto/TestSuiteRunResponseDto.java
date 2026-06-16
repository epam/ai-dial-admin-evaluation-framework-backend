package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteRunResponseDto {

    private UUID id;
    private UUID testSuiteId;
    private String testRunName;
    private String status;
    private RunConfigDto runConfig;
    private int numberOfTestCases;

    @Schema(
            description = "Execution-relevant suite configuration captured at snapshot phase. "
                    + "Present only in detail responses (GET /runs/{runId}); null in list responses.")
    private SuiteSnapshotDto suiteSnapshot;

    private Long startedAt;
    private Long completedAt;
    private String errorMessage;
    private RunErrorDetailsDto errorDetails;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Grafana Explore URL for all traces in this run "
                    + "(present only when Grafana integration is configured and run has started)",
            example = "http://grafana:3000/explore?...")
    private String grafanaExploreUrl;

    private Long createdAt;
    private Long updatedAt;
}
