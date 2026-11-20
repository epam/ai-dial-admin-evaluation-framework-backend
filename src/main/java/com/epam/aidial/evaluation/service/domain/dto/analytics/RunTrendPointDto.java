package com.epam.aidial.evaluation.service.domain.dto.analytics;

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
@Schema(description = "One completed run of a suite at its latest computation")
public class RunTrendPointDto {

    @Schema(description = "Run id", example = "3f2b8c1e-5a4d-4e7b-9c1a-2d6e8f0a1b3c")
    private UUID runId;

    @Schema(description = "Run creation time, epoch milliseconds", example = "1790000000000")
    private Long createdAt;

    @Schema(description = "passedCount divided by totalCount (0 to 1); absent when totalCount is 0", example = "0.8")
    private Double passRate;

    @Schema(description = "Test cases that passed", example = "8")
    private int passedCount;

    @Schema(description = "Test cases counted for the run", example = "10")
    private int totalCount;
}
