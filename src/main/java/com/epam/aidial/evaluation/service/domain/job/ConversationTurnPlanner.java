package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Derives the per-test-case conversation turn plan for a multi-step suite. The number of turns comes from
 * the array-valued columns referenced by {@code dataField} bindings: all such columns must share a single
 * non-zero length within {@link ValidationConstants#MAX_CONVERSATION_STEPS}. Scalar columns and
 * {@code constantValue} bindings are ignored here — they broadcast unchanged on every turn (see
 * {@link TurnPlan#project(Map, int)}). A length mismatch, an empty/absent array column, or a count over the
 * cap yields an error plan that fails only the owning test case.
 */
@Component
@LogExecution
public class ConversationTurnPlanner {

    public TurnPlan plan(List<InputBindingDto> bindings, Map<String, Object> data) {
        final Map<String, Integer> arrayFieldLengths = new LinkedHashMap<>();
        if (bindings != null) {
            for (InputBindingDto binding : bindings) {
                if (binding == null
                        || binding.getDataField() == null
                        || binding.getDataField().isBlank()) {
                    continue;
                }
                if (data.get(binding.getDataField()) instanceof List<?> list) {
                    arrayFieldLengths.put(binding.getDataField(), list.size());
                }
            }
        }

        if (arrayFieldLengths.isEmpty()) {
            return TurnPlan.error(
                    "Multi-step suite requires at least one array-valued bound column; none found in test-case data");
        }

        final Set<Integer> distinctLengths = new HashSet<>(arrayFieldLengths.values());
        if (distinctLengths.size() > 1) {
            return TurnPlan.error("Array-valued bound columns must have equal length; got " + arrayFieldLengths);
        }

        final int turnCount = distinctLengths.iterator().next();
        if (turnCount == 0) {
            return TurnPlan.error("Array-valued bound columns are empty; no conversation turns to run");
        }

        if (turnCount > ValidationConstants.MAX_CONVERSATION_STEPS) {
            return TurnPlan.error("Conversation turn count " + turnCount + " exceeds the maximum of "
                    + ValidationConstants.MAX_CONVERSATION_STEPS);
        }

        return TurnPlan.of(turnCount, arrayFieldLengths.keySet());
    }
}
