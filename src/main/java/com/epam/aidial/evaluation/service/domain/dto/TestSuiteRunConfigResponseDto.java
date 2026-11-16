package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Run-related limits currently enforced by the evaluation backend")
public class TestSuiteRunConfigResponseDto {

    @Schema(
            example = "5",
            description = "Maximum accepted value of runConfig.execution.concurrencyLevel "
                    + "(configured via test-suite-run.execution.max-concurrency-level)")
    private Integer maxConcurrencyLevel;
}
