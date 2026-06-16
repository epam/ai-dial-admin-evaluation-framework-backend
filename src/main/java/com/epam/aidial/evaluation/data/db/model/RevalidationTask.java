package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevalidationTask {

    private UUID id;
    private UUID datasetId;
    private String status;
    private int totalCases;
    private int processedCases;
    private int validCount;
    private int invalidCount;
    private Long startedAtMs;
    private Long completedAtMs;
    private String errorMessage;

    @Builder.Default
    private Long coercedCellCount = 0L;
}
