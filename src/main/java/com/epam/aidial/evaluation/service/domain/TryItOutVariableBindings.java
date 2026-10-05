package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Converts the try-it-out {@code variables} object (request index → variable name → value) into constant
 * input bindings per request, rejecting request indices outside the suite's chain.
 */
@Component
@LogExecution
public class TryItOutVariableBindings {

    /**
     * Returns request index to constant bindings for every index {@code 0..requestCount-1}; an absent or
     * null entry yields an empty list. Blank names and null values are skipped.
     *
     * @throws ValidationException for the first null or out-of-range request index in iteration order
     */
    public Map<Integer, List<InputBindingDto>> toBindingsByRequest(
            Map<Integer, Map<String, Object>> variables, int requestCount) {
        final Map<Integer, Map<String, Object>> source = variables != null ? variables : Map.of();
        validateIndices(source, requestCount);

        final Map<Integer, List<InputBindingDto>> result = new LinkedHashMap<>();
        for (int index = 0; index < requestCount; index++) {
            result.put(index, toBindings(source.get(index)));
        }
        return result;
    }

    private void validateIndices(Map<Integer, Map<String, Object>> variables, int requestCount) {
        for (Integer key : variables.keySet()) {
            if (key == null) {
                throw new ValidationException("variables: request index must not be null");
            }
            if (key < 0 || key >= requestCount) {
                throw new ValidationException(
                        "variables: request index " + key + " is out of range (chain length " + requestCount + ")");
            }
        }
    }

    private List<InputBindingDto> toBindings(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return List.of();
        }
        final List<InputBindingDto> bindings = new ArrayList<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            bindings.add(InputBindingDto.builder()
                    .templateVariable(entry.getKey())
                    .constantValue(entry.getValue())
                    .build());
        }
        return bindings;
    }
}
