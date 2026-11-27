package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
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
@Schema(description = "A schedule that runs a test suite, as stored in the DIAL Core scheduler")
public class TestSuiteScheduleResponseDto {

    @Schema(description = "Schedule id assigned by the scheduler", example = "7c9d1a52-5d4e-4a52-9d0e-0a1f6a3b2c10")
    private String id;

    @Schema(example = "Nightly regression run")
    private String name;

    private String description;

    @Schema(description = "Id of the test suite the schedule runs", example = "eab8072b-1168-4e18-9dcc-32deba20d30c")
    private UUID testSuiteId;

    private ScheduleCronDto cron;

    @Schema(example = "Europe/Berlin")
    private String timezone;

    private RunConfigDto runConfig;

    @Schema(
            description =
                    "True when the scheduler has no next fire time (paused, or the trigger has ended). Derived from"
                            + " nextRunTime being absent",
            example = "false")
    private boolean paused;

    @Schema(description = "Next fire time in epoch milliseconds; absent when paused", example = "1764118800000")
    private Long nextRunTime;

    @Schema(description = "Author recorded when the schedule was created through this service", example = "alice")
    private String createdBy;

    @Schema(description = "Creation time in epoch milliseconds", example = "1764072000000")
    private Long createdAt;

    @Schema(description = "Last update time in epoch milliseconds", example = "1764072000000")
    private Long updatedAt;
}
