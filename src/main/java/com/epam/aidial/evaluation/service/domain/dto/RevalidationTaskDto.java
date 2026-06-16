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
public class RevalidationTaskDto {

    private UUID taskId;
    private UUID datasetId;
    private RevalidationStatus status;
    private Integer totalCases;
    private Integer processedCases;
    private Integer validCount;
    private Integer invalidCount;
    private Long startedAt;
    private Long completedAt;
    private String errorMessage;

    @Schema(
            example = "42",
            description = "Total number of (row, field) cells auto-coerced during this revalidation. "
                    + "Counts cells, not rows.")
    private Long coercedCellCount;
}
