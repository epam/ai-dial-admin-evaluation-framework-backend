package com.epam.aidial.evaluation.service.domain.dto;

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
@Schema(description = "One firing of a schedule, as recorded by the DIAL Core scheduler")
public class ScheduleRunDto {

    @Schema(example = "0b9a1c6e-3d0f-4f64-8f0e-3d3f2d7f9a11")
    private String id;

    @Schema(description = "One of success, error, in_progress, missed", example = "success")
    private String status;

    @Schema(description = "Start time in epoch milliseconds", example = "1764118800000")
    private Long startTime;

    @Schema(description = "End time in epoch milliseconds; absent while in progress", example = "1764118803000")
    private Long endTime;

    @Schema(
            description = "Id of the EF run this fire started; absent when the fire did not reach EF or its recorded"
                    + " response could not be read",
            example = "3f0c6d3e-6a43-4a4f-9f0e-0c2b0d6a0001")
    private UUID testSuiteRunId;

    @Schema(
            description = "Current status of that EF run, read live from EF; absent when there is no run id or the run"
                    + " was deleted",
            example = "COMPLETED")
    private String testSuiteRunStatus;
}
