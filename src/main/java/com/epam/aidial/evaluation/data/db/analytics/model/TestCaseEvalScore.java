package com.epam.aidial.evaluation.data.db.analytics.model;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code evalSummaryId} is an old/new-format discriminator, not part of this row's real identity
 * anymore: {@code null} for every row written from now on (identity is {@code testSuiteRunId} +
 * {@code testCaseId} + {@code computationId}), populated only on legacy rows written before this
 * table was re-keyed. {@code id} is the surrogate primary key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseEvalScore {
    private UUID id;
    private UUID evalSummaryId;
    private UUID testSuiteRunId;
    private UUID testCaseId;
    private String testCaseName;
    private UUID computationId;
    private ExecutionStatus executionStatus;
    private Double score;
    private Boolean passed;
    private Long computedAtMs;
}
