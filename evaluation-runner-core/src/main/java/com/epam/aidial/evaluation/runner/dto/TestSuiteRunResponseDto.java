package com.epam.aidial.evaluation.runner.dto;

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

    @Schema(
            description = "Run lifecycle status. CANCELLING is a transient state entered when a RUNNING run is"
                    + " cancelled; the async job finalizes it to CANCELLED.",
            allowableValues = {"PENDING", "RUNNING", "CANCELLING", "COMPLETED", "FAILED", "CANCELLED"},
            example = "RUNNING")
    private String status;

    private RunConfigDto runConfig;
    private int numberOfTestCases;

    @Schema(
            description = "Execution-relevant suite configuration captured at snapshot phase. "
                    + "Present only in detail responses (GET /runs/{runId}); null in list responses.")
    private SuiteSnapshotDto suiteSnapshot;

    @Schema(
            description = "Execution start, epoch milliseconds. Null only while the run is PENDING. For a run that"
                    + " ended without ever running (failed or cancelled while PENDING) it equals completedAt.",
            example = "1760000000000")
    private Long startedAt;

    @Schema(
            description = "Run end, epoch milliseconds. Null until the run reaches a terminal status.",
            example = "1760000060000")
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
