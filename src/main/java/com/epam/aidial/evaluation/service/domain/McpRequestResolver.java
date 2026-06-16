package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves MCP tool arguments by substituting ${{variable}} and ${{variable:default}}
 * placeholders in the argument template with values from the test case data.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class McpRequestResolver {

    // Matches ${{varName}}, ${{varName:default}}, ${{varName|type}}, ${{varName|type:default}}
    // Group 1: variable name, Group 2: type hint (optional), Group 3: default value (optional)
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$\\{\\{([^:|}]+)(?:\\|([^:}]+))?(?::([^}]*))?\\}\\}");

    /**
     * Matches a string that is exactly one placeholder (for type preservation).
     * Uses [^}]+ which already covers type-hinted placeholders like ${{var|file}}.
     */
    private static final Pattern FULL_VALUE_PATTERN = Pattern.compile("^\\$\\{\\{[^}]+\\}\\}$");

    private final DialFileRefResolver dialFileRefResolver;

    /**
     * Resolution result carrying resolved arguments and any warnings.
     */
    @Getter
    @Builder
    public static class ResolutionResult {
        private final Map<String, Object> arguments;
        private final List<ValidationWarningDto> warnings;
    }

    /**
     * Resolves argument template variables using input bindings and test case data.
     * Resolution priority per variable: binding constantValue > binding dataField lookup > direct
     * variable name lookup > template default > null + REQUIRED warning.
     *
     * @param argumentTemplate the argument template with variable placeholders
     * @param bindings         input bindings (may be null or empty)
     * @param testCaseData     the test case data map (field name -> value)
     * @return resolution result with resolved arguments and any warnings
     */
    public ResolutionResult resolve(
            ArgumentTemplateDto argumentTemplate, List<InputBindingDto> bindings, Map<String, Object> testCaseData) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        if (argumentTemplate == null || argumentTemplate.getArguments() == null) {
            return ResolutionResult.builder()
                    .arguments(Map.of())
                    .warnings(warnings)
                    .build();
        }

        Map<String, Object> data = testCaseData != null ? testCaseData : Map.of();
        Map<String, InputBindingDto> bindingByVar = toBindingMap(bindings);
        Map<String, Object> resolved = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : argumentTemplate.getArguments().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            resolved.put(key, resolveValue(value, bindingByVar, data, warnings));
        }

        return ResolutionResult.builder().arguments(resolved).warnings(warnings).build();
    }

    /**
     * Resolves arguments using a direct variables map (for try-it-out with variables mode).
     * Variables are treated as constant-value bindings — no data lookup indirection.
     */
    public ResolutionResult resolveWithVariables(
            ArgumentTemplateDto argumentTemplate, List<InputBindingDto> bindings, Map<String, Object> variables) {
        return resolve(argumentTemplate, bindings, variables);
    }

    private Object resolveValue(
            Object value,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        if (value instanceof String strValue) {
            return resolveStringValue(strValue, bindingByVar, data, warnings);
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                resolvedMap.put(
                        String.valueOf(entry.getKey()), resolveValue(entry.getValue(), bindingByVar, data, warnings));
            }
            return resolvedMap;
        }
        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(item -> resolveValue(item, bindingByVar, data, warnings))
                    .toList();
        }
        // Numbers, booleans, null - pass through
        return value;
    }

    private Object resolveStringValue(
            String strValue,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(strValue);

        // If the entire string is a single variable reference, preserve type
        if (FULL_VALUE_PATTERN.matcher(strValue).matches() && matcher.matches()) {
            String varName = matcher.group(1).trim();
            String typeHint = matcher.group(2);
            String defaultValue = matcher.group(3);

            Object resolved = resolveVariable(varName, bindingByVar, data);
            if (resolved != null) {
                return resolveFileRef(resolved, typeHint);
            }
            if (defaultValue != null) {
                Object parsedDefault = parseDefaultValue(defaultValue);
                return resolveFileRef(parsedDefault, typeHint);
            }
            // Variable not found and no default — add REQUIRED warning
            warnings.add(ValidationWarningDto.builder()
                    .fieldName(varName)
                    .path("$.argumentTemplate.arguments")
                    .message("Required variable '" + varName + "' not found in test case data")
                    .code(ValidationWarningCode.REQUIRED)
                    .build());
            return null;
        }

        // If string contains variable references mixed with text, do string substitution
        // (no file ref resolution for embedded placeholders)
        matcher.reset();
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String defaultValue = matcher.group(3);

            Object replacement = resolveVariable(varName, bindingByVar, data);
            if (replacement == null) {
                if (defaultValue != null) {
                    replacement = defaultValue;
                } else {
                    warnings.add(ValidationWarningDto.builder()
                            .fieldName(varName)
                            .path("$.argumentTemplate.arguments")
                            .message("Required variable '" + varName + "' not found in test case data")
                            .code(ValidationWarningCode.REQUIRED)
                            .build());
                    replacement = "";
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(replacement)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves a file reference if the type hint is "file" and the value is a String.
     * Converts short-format references to DIAL API paths via {@link DialFileRefResolver}.
     */
    private Object resolveFileRef(Object resolved, String typeHint) {
        if (typeHint != null
                && SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint.trim())
                && resolved instanceof String resolvedRef) {
            return dialFileRefResolver.resolveToDialRef(resolvedRef);
        }
        return resolved;
    }

    /**
     * Resolves a single variable using the binding priority chain:
     * 1. Binding constantValue (if binding exists and has constantValue)
     * 2. Binding dataField lookup (if binding exists and has dataField)
     * 3. Direct variable name lookup in data (fallback when no binding)
     *
     * @return resolved value, or null if unresolved
     */
    private Object resolveVariable(
            String varName, Map<String, InputBindingDto> bindingByVar, Map<String, Object> data) {
        InputBindingDto binding = bindingByVar.get(varName);
        if (binding != null) {
            if (binding.getConstantValue() != null) {
                return binding.getConstantValue();
            }
            if (binding.getDataField() != null && !binding.getDataField().isBlank()) {
                if (data.containsKey(binding.getDataField())) {
                    return data.get(binding.getDataField());
                }
                return null;
            }
        }
        // No binding — fall back to direct variable name lookup
        if (data.containsKey(varName)) {
            return data.get(varName);
        }
        return null;
    }

    private static Map<String, InputBindingDto> toBindingMap(List<InputBindingDto> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return Map.of();
        }
        return bindings.stream()
                .filter(b -> b != null && b.getTemplateVariable() != null)
                .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));
    }

    /**
     * Parses a default value string, attempting to preserve numeric and boolean types.
     */
    private static Object parseDefaultValue(String defaultValue) {
        if (defaultValue.isEmpty()) {
            return "";
        }
        // Try integer
        try {
            return Long.parseLong(defaultValue);
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        // Try decimal
        try {
            return Double.parseDouble(defaultValue);
        } catch (NumberFormatException ignored) {
            // not a decimal
        }
        // Try boolean
        if ("true".equalsIgnoreCase(defaultValue)) {
            return true;
        }
        if ("false".equalsIgnoreCase(defaultValue)) {
            return false;
        }
        // Return as string
        return defaultValue;
    }
}
