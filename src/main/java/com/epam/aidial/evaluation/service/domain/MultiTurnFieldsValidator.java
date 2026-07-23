package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Write-time validation of the array-based multi-turn authoring fields on a test case. Enforces the two
 * hard invariants as 400s: {@code data} and {@code multiTurnData} are mutually exclusive (a multi-turn case
 * carries no single-turn {@code data}), and a present {@code multiTurnData} array must be non-empty.
 *
 * <p>The configurable max-turns cap is <b>not</b> a 400 — an over-cap case is persisted but invalidated
 * (see {@code TestCaseValidationService}), so a bad CSV row never fails the whole import. This validator
 * exposes {@link #getMaxTurns()} so the validation service can add the invalidating warning.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class MultiTurnFieldsValidator {

    private final TestCaseProperties testCaseProperties;

    /**
     * Validates the effective (post-merge) {@code data}/{@code multiTurnData} pair. Rejects with 400 when
     * both are populated together, or when {@code multiTurnData} is present but empty.
     */
    public void validateStructure(Map<String, Object> data, List<Map<String, Object>> multiTurnData) {
        final boolean hasData = data != null && !data.isEmpty();
        final boolean hasMultiTurn = multiTurnData != null;
        if (hasMultiTurn && multiTurnData.isEmpty()) {
            throw new ValidationException("multiTurnData must contain at least one turn");
        }
        if (hasData && hasMultiTurn) {
            throw new ValidationException("data and multiTurnData are mutually exclusive");
        }
    }

    /** Configured maximum number of turns a multi-turn case may carry before it is invalidated. */
    public int getMaxTurns() {
        return testCaseProperties.getMultiTurn().getMaxTurns();
    }
}
