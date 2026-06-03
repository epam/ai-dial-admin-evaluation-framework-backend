package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Execution settings for a test suite run")
public class ExecutionSettingsDto {

    @Min(value = 1, message = "concurrencyLevel must be at least 1")
    @Schema(description = "Number of parallel test case executions", example = "1")
    private Integer concurrencyLevel;

    @Min(value = 1000, message = "requestTimeoutMs must be at least 1000")
    @Schema(description = "Per-request timeout in milliseconds", example = "30000")
    private Long requestTimeoutMs;

    @DecimalMin(value = "0.1", message = "rateLimitRps must be at least 0.1")
    @Schema(description = "Rate limit in requests per second (null = no limit)", example = "5.0")
    private Double rateLimitRps;
}
