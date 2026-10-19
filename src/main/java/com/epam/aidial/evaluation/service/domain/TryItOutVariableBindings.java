package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.service.FilePlaceholderDetector;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Converts the try-it-out {@code variables} object (request index → variable name → value) into constant
 * input bindings per request, rejecting request indices outside the suite's chain.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TryItOutVariableBindings {

    private final FilePlaceholderDetector filePlaceholderDetector;

    /**
     * Bindings plus the data map they resolve against for one request of a variables-mode try-out.
     */
    public record VariableResolution(List<InputBindingDto> bindings, Map<String, Object> data) {}

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

    /**
     * Keeps a supplied variable bound to the suite's FILE column so it resolves like in a real run: when the
     * suite's own binding for the variable names a {@code FILE} schema field, the constant binding becomes
     * a {@code dataField} binding on that field and the value moves into the returned data map under the
     * field name, letting {@code FilePlaceholderDetector} see the column type. Every other variable stays
     * a constant binding (and needs an explicit {@code |file} hint, as before). A second variable bound to
     * a column whose data entry is already taken also stays constant.
     *
     * @param constantBindings the request's constant bindings built from the supplied variables
     * @param suiteBindings    the request's own {@code inputBindings} as saved on the suite (nullable)
     * @param schema           the dataset test case schema (nullable)
     */
    public VariableResolution bindToFileColumns(
            List<InputBindingDto> constantBindings,
            List<InputBindingDto> suiteBindings,
            List<FieldDefinitionDto> schema) {
        final Map<String, InputBindingDto> suiteBindingByVariable = new HashMap<>();
        if (suiteBindings != null) {
            for (InputBindingDto binding : suiteBindings) {
                if (binding != null && binding.getTemplateVariable() != null) {
                    suiteBindingByVariable.putIfAbsent(binding.getTemplateVariable(), binding);
                }
            }
        }

        final List<InputBindingDto> bindings = new ArrayList<>();
        final Map<String, Object> data = new HashMap<>();
        for (InputBindingDto constant : constantBindings) {
            final InputBindingDto suiteBinding = suiteBindingByVariable.get(constant.getTemplateVariable());
            final boolean fileColumn = filePlaceholderDetector
                    .boundFieldType(suiteBinding, schema)
                    .filter(SchemaFieldType.FILE::equals)
                    .isPresent();
            if (fileColumn && !data.containsKey(suiteBinding.getDataField())) {
                data.put(suiteBinding.getDataField(), constant.getConstantValue());
                bindings.add(InputBindingDto.builder()
                        .templateVariable(constant.getTemplateVariable())
                        .dataField(suiteBinding.getDataField())
                        .build());
            } else {
                bindings.add(constant);
            }
        }
        return new VariableResolution(bindings, data);
    }
}
