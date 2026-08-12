package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Write-time structural validation of the array-based multi-turn authoring fields on a test case.
 * {@code data} (shared, test-case-level fields) and {@code multiTurnData} (per-turn fields) MAY coexist —
 * they are no longer mutually exclusive. This validator enforces the remaining hard invariants as 400s:
 *
 * <ul>
 *   <li>a present {@code multiTurnData} array must be non-empty;
 *   <li><b>scope placement</b> — a per-turn field must not appear in the shared {@code data} map, and a
 *       shared field must not appear in any turn map. The rule itself is owned by {@link
 *       TestCaseDataScopeResolver#requireCorrectScope}; this validator only delegates to it.
 * </ul>
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
    private final TestCaseDataScopeResolver dataScopeResolver;

    /**
     * Validates the {@code data}/{@code multiTurnData} pair against the dataset schema. Rejects with 400
     * when {@code multiTurnData} is present but empty, or when a field is placed in the wrong scope bucket
     * (see {@link TestCaseDataScopeResolver#requireCorrectScope}).
     */
    public void validateStructure(
            Map<String, Object> data, List<Map<String, Object>> multiTurnData, List<FieldDefinitionDto> schema) {
        if (multiTurnData == null) {
            return;
        }
        if (multiTurnData.isEmpty()) {
            throw new ValidationException("multiTurnData must contain at least one turn");
        }

        dataScopeResolver.requireCorrectScope(data, multiTurnData, schema);
    }

    /** Configured maximum number of turns a multi-turn case may carry before it is invalidated. */
    public int getMaxTurns() {
        return testCaseProperties.getMultiTurn().getMaxTurns();
    }
}
