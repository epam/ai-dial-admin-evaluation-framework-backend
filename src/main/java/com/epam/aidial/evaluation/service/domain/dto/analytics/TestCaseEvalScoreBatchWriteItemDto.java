package com.epam.aidial.evaluation.service.domain.dto.analytics;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseEvalScoreBatchWriteItemDto {
    private UUID evalSummaryId;
    private Double score;
    private Boolean passed;
}
