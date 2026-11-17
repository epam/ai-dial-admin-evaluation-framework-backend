package com.epam.aidial.evaluation.runner.service;

import static org.apache.commons.lang3.StringUtils.EMPTY;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Shared component for resolving template variable values.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Binding with constantValue → always wins</li>
 *   <li>Binding with dataField → use data[dataField] if present</li>
 *   <li>Template default → fallback</li>
 *   <li>No binding + no default → null (with warning)</li>
 * </ol>
 */
@Component
@LogExecution
public class TemplateVariableResolver {

    /**
     * Resolves a variable without treating empty strings as absent.
     */
    public Object resolveVariable(
            String varName,
            String defaultValue,
            InputBindingDto binding,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        return resolveVariable(varName, defaultValue, binding, data, warnings, false);
    }

    /**
     * Resolves a variable, optionally treating an empty resource value as absent before default selection.
     * An empty constant still takes priority over bound data: it falls back to the template default, not
     * to the data field. Callers enable this only for full-value FILE and SKILL placeholders.
     *
     * @param emptyAsAbsent whether an empty string has the same semantics as a missing value
     */
    public Object resolveVariable(
            String varName,
            String defaultValue,
            InputBindingDto binding,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings,
            boolean emptyAsAbsent) {
        final Object value = resolveBoundValue(binding, data);
        if (value == null || (emptyAsAbsent && EMPTY.equals(value))) {
            return resolveAbsentValue(varName, defaultValue, binding, warnings);
        }
        return value;
    }

    private Object resolveBoundValue(InputBindingDto binding, Map<String, Object> data) {
        if (binding == null) {
            return null;
        }
        final Object constantValue = binding.getConstantValue();
        if (constantValue != null) {
            return constantValue;
        }
        final String dataField = binding.getDataField();
        if (data == null || dataField == null || dataField.isBlank()) {
            return null;
        }
        return data.get(dataField);
    }

    private Object resolveAbsentValue(
            String varName, String defaultValue, InputBindingDto binding, List<ValidationWarningDto> warnings) {
        if (defaultValue != null) {
            return defaultValue;
        }
        if (binding != null && binding.getDataField() != null && !binding.getDataField().isBlank()) {
            warnings.add(warning(
                    binding.getDataField(),
                    "$.data." + binding.getDataField(),
                    "Bound field '" + binding.getDataField() + "' has no value for variable '" + varName + "'",
                    ValidationWarningCode.REQUIRED));
        } else {
            warnings.add(warning(
                    varName,
                    "$.inputBindings",
                    "Required template variable '" + varName + "' has no binding and no default",
                    ValidationWarningCode.REQUIRED));
        }
        return null;
    }

    private static ValidationWarningDto warning(
            String fieldName, String path, String message, ValidationWarningCode code) {
        return ValidationWarningDto.builder()
                .fieldName(fieldName)
                .path(path)
                .message(message)
                .code(code)
                .build();
    }
}
