package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
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
     * Resolves a single template variable value.
     *
     * @param varName      the template variable name
     * @param defaultValue the template default (from {@code ${{var:default}}}), nullable
     * @param binding      the input binding for this variable, nullable
     * @param data         test case data map — nullable; when null (suite-level),
     *                     data-field bindings resolve to null (fall through to default)
     * @param warnings     accumulator for resolution warnings
     * @return the resolved value, or null if unresolvable
     */
    public Object resolveVariable(
            String varName,
            String defaultValue,
            InputBindingDto binding,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        Map<String, Object> safeData = data != null ? data : Map.of();

        if (binding != null) {
            // constantValue wins
            if (binding.getConstantValue() != null) {
                return binding.getConstantValue();
            }
            // dataField
            if (binding.getDataField() != null && !binding.getDataField().isBlank()) {
                Object value = safeData.get(binding.getDataField());
                if (value != null) {
                    return value;
                }
                // data field is null, fall through to default
                if (defaultValue != null) {
                    return defaultValue;
                }
                warnings.add(warning(
                        binding.getDataField(),
                        "$.data." + binding.getDataField(),
                        "Bound field '" + binding.getDataField() + "' has no value for variable '" + varName + "'",
                        ValidationWarningCode.REQUIRED));
                return null;
            }
        }

        // No binding
        if (defaultValue != null) {
            return defaultValue;
        }

        warnings.add(warning(
                varName,
                "$.inputBindings",
                "Required template variable '" + varName + "' has no binding and no default",
                ValidationWarningCode.REQUIRED));
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
