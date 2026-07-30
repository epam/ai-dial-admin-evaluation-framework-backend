package com.epam.aidial.evaluation.runner.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseRunInput {

    private UUID runId;
    private int position;
    private UUID testCaseId;
    private String testCaseName;
    private String testCaseData;

    /**
     * Frozen JSON array of turn-data maps for a multi-turn test case (snapshot of the case's
     * {@code multiTurnData}). Null for a single-turn input (the scalar {@code testCaseData} path is used).
     */
    private String multiTurnData;

    private String requestTemplateOverride;
    private String inputBindingsOverride;
}
