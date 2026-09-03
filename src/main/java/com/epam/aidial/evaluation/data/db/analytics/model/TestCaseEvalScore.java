package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseEvalScore {
    private UUID evalSummaryId;
    private Double score;
    private Boolean passed;
    private Long computedAtMs;
}
