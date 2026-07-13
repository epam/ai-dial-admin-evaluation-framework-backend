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

    /** Row-based multi-turn: conversation grouping key for a multi-turn unit; {@code null} for single-turn. */
    private UUID conversationId;
    /** Surviving (post-truncation) turn count of the assembled conversation; {@code null} for single-turn. */
    private Integer totalTurns;
    /** Ordered assembled turns as JSON ({@code [{testCaseId, turnIndex, data}, ...]}); {@code null} for single-turn. */
    private String turns;
    /** Broken-conversation marker: executor emits one {@code 0/0} ERROR row without invoking the model. */
    private boolean broken;
}
