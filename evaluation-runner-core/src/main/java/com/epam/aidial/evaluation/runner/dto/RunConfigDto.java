package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
@Schema(description = "Run configuration for a test suite evaluation")
public class RunConfigDto {

    @NotNull
    @Min(1)
    @Schema(description = "Number of runs per test case", example = "1")
    private Integer numberOfRuns;

    @Size(max = ValidationConstants.MAX_TEST_RUN_NAME_LENGTH)
    @Schema(description = "User-provided name for the run (auto-generated if omitted)")
    private String testRunName;

    @Valid
    @Schema(
            description =
                    "Execution settings (concurrency, timeout, rate limiting). All optional with system defaults.")
    private ExecutionSettingsDto execution;

    @Valid
    @Schema(description = "Retry policy for failed calls. All optional with system defaults.")
    private RetryPolicyDto retry;
}
