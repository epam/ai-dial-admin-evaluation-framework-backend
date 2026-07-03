package com.epam.aidial.evaluation.service.domain.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolved conversation turn plan for a single multi-step test case: a valid turn count together with the
 * names of the array-valued (iterating) bound columns, or an error message describing why a plan could not
 * be derived from the test-case data.
 */
public record TurnPlan(int turnCount, Set<String> iteratingFields, String error) {

    static TurnPlan of(int turnCount, Set<String> iteratingFields) {
        return new TurnPlan(turnCount, iteratingFields, null);
    }

    static TurnPlan error(String message) {
        return new TurnPlan(0, Set.of(), message);
    }

    public boolean hasError() {
        return error != null;
    }

    /** Builds turn {@code i}'s data: iterating (array-valued) fields → element {@code i}; all others unchanged. */
    public Map<String, Object> project(Map<String, Object> data, int i) {
        final Map<String, Object> perTurn = new LinkedHashMap<>(data);
        for (String field : iteratingFields) {
            if (data.get(field) instanceof List<?> list) {
                perTurn.put(field, list.get(i));
            }
        }
        return perTurn;
    }
}
