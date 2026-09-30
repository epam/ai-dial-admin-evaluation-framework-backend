package com.epam.aidial.evaluation.data.db.analytics.model;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code id} is the surrogate primary key; identity is {@code testSuiteRunId} + {@code testCaseId} + {@code computationId}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseEvalScore {
    private UUID id;
    private UUID testSuiteRunId;
    private UUID testCaseId;
    private String testCaseName;
    private UUID computationId;
    private ExecutionStatus executionStatus;
    private Double score;
    private Boolean passed;
    private Long computedAtMs;
}
