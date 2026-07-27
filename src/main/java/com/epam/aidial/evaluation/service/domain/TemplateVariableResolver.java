package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Shared component for resolving template variable values.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Binding with constantValue → always wins</li>
 *   <li>Binding with dataField → use data[dataField] if present</li>
 *   <li>Binding with responseField → use the earlier chain request's extracted value if present</li>
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
     * @param scope        the values this variable may resolve against — test-case data and, for a chain
     *                     request, the response values extracted by earlier requests
     * @param warnings     accumulator for resolution warnings
     * @return the resolved value, or null if unresolvable
     */
    public Object resolveVariable(
            String varName,
            String defaultValue,
            InputBindingDto binding,
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        ResolutionScope safeScope = scope != null ? scope : ResolutionScope.empty();

        if (binding != null) {
            // constantValue wins
            if (binding.getConstantValue() != null) {
                return binding.getConstantValue();
            }
            // dataField
            if (binding.getDataField() != null && !binding.getDataField().isBlank()) {
                Object value = safeScope.safeData().get(binding.getDataField());
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
            // responseField — a value extracted by a strictly earlier chain request. Mirrors dataField's
            // fallback: the placeholder's declared default IS the author's statement of what to do when the
            // value is missing, so honoring it makes safe continuation opt-in and visible in the template.
            // Without a default the chain executor treats this as a request failure rather than sending a
            // semantically nonsense call that would return 200 and score garbage.
            if (binding.getResponseField() != null
                    && !binding.getResponseField().isBlank()) {
                Object value = safeScope.safeResponseValues().get(binding.getResponseField());
                if (value != null) {
                    return value;
                }
                if (defaultValue != null) {
                    return defaultValue;
                }
                warnings.add(warning(
                        binding.getResponseField(),
                        "$.response." + binding.getResponseField(),
                        "Response column '" + binding.getResponseField()
                                + "' from an earlier chain request has no value for variable '" + varName + "'",
                        ValidationWarningCode.UNRESOLVED_REFERENCE));
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
