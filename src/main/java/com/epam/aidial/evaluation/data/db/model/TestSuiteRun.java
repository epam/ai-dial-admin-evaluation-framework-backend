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
public class TestSuiteRun {

    private UUID id;
    private UUID testSuiteId;
    private String testRunName;
    private String status;
    private String runConfig;
    private int numberOfTestCases;
    private Long startedAt;
    private Long completedAt;
    private String errorMessage;
    private String errorDetails;
    private String suiteSnapshot;
    private Long createdAt;
    private Long updatedAt;
}
