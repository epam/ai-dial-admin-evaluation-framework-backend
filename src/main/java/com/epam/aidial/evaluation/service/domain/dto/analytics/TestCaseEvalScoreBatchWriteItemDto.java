package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
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
    private UUID testSuiteRunId;
    private UUID testCaseId;
    private String testCaseName;
    private UUID computationId;
    private ExecutionStatus executionStatus;
    private Double score;
    private Boolean passed;
}
