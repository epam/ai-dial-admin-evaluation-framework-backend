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
public class TestCaseRunInput {

    private UUID runId;
    private int position;
    private UUID testCaseId;
    private String testCaseName;
    private String testCaseData;
    private String requestTemplateOverride;
    private String inputBindingsOverride;

    private UUID conversationId;
    private Integer totalTurns;
    private String turns;
    private boolean broken;
}
