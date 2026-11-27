package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Creates or replaces a schedule that runs a test suite on a cron trigger")
public class TestSuiteScheduleRequestDto {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Display name of the schedule", example = "Nightly regression run")
    private String name;

    @Size(max = 2000)
    @Schema(description = "Optional description", example = "Runs the regression suite every night")
    private String description;

    @NotNull
    @Valid
    @Schema(description = "Cron trigger")
    private ScheduleCronDto cron;

    @Size(max = 64)
    @Schema(
            description = "IANA time zone the cron fields are interpreted in; defaults to UTC",
            example = "Europe/Berlin")
    private String timezone;

    @NotNull
    @Valid
    @Schema(description = "Run configuration used for every scheduled run")
    private RunConfigDto runConfig;
}
