package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
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
@Schema(description = "Retry policy for failed test case executions")
public class RetryPolicyDto {

    @Min(value = 0, message = "maxRetries must be at least 0")
    @Schema(description = "Maximum number of retries per call (0 = no retry)", example = "0")
    private Integer maxRetries;

    @Min(value = 100, message = "retryDelayMs must be at least 100")
    @Schema(description = "Base delay between retries in milliseconds", example = "1000")
    private Long retryDelayMs;

    @DecimalMin(value = "1.0", message = "retryBackoffMultiplier must be at least 1.0")
    @DecimalMax(value = "10.0", message = "retryBackoffMultiplier must be at most 10.0")
    @Schema(description = "Exponential backoff multiplier for retry delays", example = "2.0")
    private Double retryBackoffMultiplier;
}
