package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Test case data-level validation: checks data against effective template/bindings and testCaseSchema.
 * Results are stored in TestCase.isValid + TestCase.validationWarnings.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class TestCaseValidationService {

    private final TemplateVariableExtractor templateVariableExtractor;
    private final ValidationProperties validationProperties;
    private final FileRefValidator fileRefValidator;
    private final TestCaseProperties testCaseProperties;
    private final TestCaseFieldScopeResolver scopeResolver;

    /**
     * Validates test case data against the effective template, bindings, and schema.
     *
     * @param data              the test case data map
     * @param testCaseSchema    field definitions from the suite
     * @param effectiveTemplate effective request template (override or suite)
     * @param effectiveBindings effective input bindings (override or suite)
     * @param hasOverrides      true if the test case has template or binding overrides
     * @param datasetId         the owning dataset ID (used for FILE-field ownership validation;
     *                          may be {@code null} to skip ownership checks)
     * @return validation result with warnings
     */
    public ValidationResult validateTestCase(
            Map<String, Object> data,
            List<FieldDefinitionDto> testCaseSchema,
            RequestTemplateDto effectiveTemplate,
            List<InputBindingDto> effectiveBindings,
            boolean hasOverrides,
            UUID datasetId) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Map<String, Object> safeData = data != null ? data : Map.of();
        List<FieldDefinitionDto> safeSchema = testCaseSchema != null ? testCaseSchema : List.of();
        List<InputBindingDto> safeBindings = effectiveBindings != null ? effectiveBindings : List.of();

        Set<String> schemaFieldNames = safeSchema.stream()
                .filter(f -> f != null && f.getName() != null)
                .map(FieldDefinitionDto::getName)
                .collect(Collectors.toSet());

        // If test case has overrides, re-check binding configuration
        if (hasOverrides) {
            List<TemplateVariableExtractor.ExtractedVariable> variables =
                    templateVariableExtractor.extract(effectiveTemplate);
            Set<String> variableNames = variables.stream()
                    .map(TemplateVariableExtractor.ExtractedVariable::getName)
                    .collect(Collectors.toSet());

            Map<String, InputBindingDto> bindingByVar = safeBindings.stream()
                    .filter(b -> b != null && b.getTemplateVariable() != null)
                    .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));

            // Required variable without binding
            for (TemplateVariableExtractor.ExtractedVariable var : variables) {
                if (!var.isHasDefault() && !bindingByVar.containsKey(var.getName())) {
                    warnings.add(warning(
                            var.getName(),
                            "$.inputBindingsOverride",
                            "Required variable '" + var.getName() + "' has no binding",
                            ValidationWarningCode.REQUIRED));
                }
            }

            // Orphan bindings
            for (InputBindingDto binding : safeBindings) {
                if (binding == null || binding.getTemplateVariable() == null) {
                    continue;
                }
                if (!variableNames.contains(binding.getTemplateVariable())) {
                    warnings.add(warning(
                            binding.getTemplateVariable(),
                            "$.inputBindingsOverride",
                            "Binding for '" + binding.getTemplateVariable() + "' but no ${{"
                                    + binding.getTemplateVariable() + "}} in template",
                            ValidationWarningCode.ADDITIONAL));
                }
                if (binding.getDataField() != null
                        && !binding.getDataField().isBlank()
                        && !schemaFieldNames.contains(binding.getDataField())) {
                    warnings.add(warning(
                            binding.getDataField(),
                            "$.inputBindingsOverride",
                            "Binding maps to unknown field '" + binding.getDataField() + "'",
                            ValidationWarningCode.UNKNOWN));
                }
            }
        }

        // Data-vs-binding: for each bound variable with dataField, check data has value
        Map<String, SchemaFieldType> fieldTypeByName = safeSchema.stream()
                .filter(f -> f != null && f.getName() != null && f.getType() != null)
                .collect(Collectors.toMap(FieldDefinitionDto::getName, FieldDefinitionDto::getType, (a, b) -> a));

        List<TemplateVariableExtractor.ExtractedVariable> variables =
                templateVariableExtractor.extract(effectiveTemplate);
        Map<String, InputBindingDto> bindingByVar = safeBindings.stream()
                .filter(b -> b != null && b.getTemplateVariable() != null)
                .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));

        for (TemplateVariableExtractor.ExtractedVariable var : variables) {
            InputBindingDto binding = bindingByVar.get(var.getName());
            if (binding != null
                    && binding.getDataField() != null
                    && !binding.getDataField().isBlank()) {
                Object value = safeData.get(binding.getDataField());
                if ((value == null
                                || (fieldTypeByName.get(binding.getDataField()) == SchemaFieldType.FILE
                                        && value instanceof String s
                                        && s.isBlank()))
                        && !var.isHasDefault()) {
                    warnings.add(warning(
                            binding.getDataField(),
                            "$.data." + binding.getDataField(),
                            "Required field '" + binding.getDataField() + "' is empty in data",
                            ValidationWarningCode.REQUIRED));
                }
            }
        }

        // Required schema fields missing from data
        for (FieldDefinitionDto field : safeSchema) {
            if (field != null && field.isRequired() && field.getName() != null) {
                Object value = safeData.get(field.getName());
                if (value == null
                        || (field.getType() == SchemaFieldType.FILE && value instanceof String s && s.isBlank())) {
                    warnings.add(warning(
                            field.getName(),
                            "$.data." + field.getName(),
                            "Required field '" + field.getName() + "' is missing from data",
                            ValidationWarningCode.REQUIRED));
                }
            }
        }

        // Unknown data fields (not in schema)
        for (String key : safeData.keySet()) {
            if (!schemaFieldNames.contains(key)) {
                warnings.add(warning(
                        key, "$.data." + key, "Unknown data field '" + key + "'", ValidationWarningCode.ADDITIONAL));
            }
        }

        // Type mismatch validation: check data value types against schema declarations
        for (FieldDefinitionDto field : safeSchema) {
            if (field == null || field.getName() == null || field.getType() == null) {
                continue;
            }
            Object value = safeData.get(field.getName());
            if (value == null) {
                continue;
            }
            if (!isTypeCompatible(value, field.getType())) {
                String actualType = value.getClass().getSimpleName();
                warnings.add(warning(
                        field.getName(),
                        "$.data." + field.getName(),
                        "Field '" + field.getName() + "' has schema type " + field.getType() + " but value is "
                                + actualType,
                        ValidationWarningCode.TYPE));
            }
        }

        // FILE field validation: check DIAL file reference format and prefix
        validateFileFields(safeData, safeSchema, datasetId, warnings);

        // Truncate warnings
        int maxWarnings = validationProperties.getMaxWarningsPerCase();
        if (warnings.size() > maxWarnings) {
            warnings.subList(maxWarnings, warnings.size()).clear();
        }

        return ValidationResult.builder()
                .valid(warnings.isEmpty())
                .warnings(List.copyOf(warnings))
                .build();
    }

    /**
     * Validates a multi-turn case scope-aware: the shared {@code data} map against the shared sub-schema
     * ({@code perTurn=false} fields) and every turn against the per-turn sub-schema ({@code perTurn=true}
     * fields), both using the dataset schema. The case is valid iff no shared-field warning and every turn
     * passes and the turn count is within the configured cap; each per-turn warning is tagged with its
     * 0-based {@code turnIndex} (shared-field warnings carry none). Exceeding the max-turns cap adds one
     * invalidating warning (not a 400). A multi-turn case with all-empty turn maps is valid when no
     * required per-turn field exists.
     */
    public ValidationResult validateMultiTurn(
            Map<String, Object> sharedData,
            List<Map<String, Object>> turns,
            List<FieldDefinitionDto> testCaseSchema,
            RequestTemplateDto effectiveTemplate,
            List<InputBindingDto> effectiveBindings,
            boolean hasOverrides,
            UUID datasetId) {
        List<Map<String, Object>> safeTurns = turns != null ? turns : List.of();

        final TestCaseFieldScopeResolver.SchemaSplit schemaSplit = scopeResolver.splitSchema(testCaseSchema);

        // Shared (test-case-level) fields are validated once against the shared sub-schema; their warnings
        // carry no turn index.
        ValidationResult sharedResult = validateTestCase(
                sharedData, schemaSplit.shared(), effectiveTemplate, effectiveBindings, hasOverrides, datasetId);

        List<ValidationWarningDto> warnings = new ArrayList<>(sharedResult.getWarnings());

        int maxTurns = testCaseProperties.getMultiTurn().getMaxTurns();
        if (safeTurns.size() > maxTurns) {
            warnings.add(warning(
                    null,
                    "$.multiTurnData",
                    "Multi-turn case has " + safeTurns.size() + " turns, exceeding the maximum of " + maxTurns,
                    ValidationWarningCode.ADDITIONAL));
        }

        for (int i = 0; i < safeTurns.size(); i++) {
            ValidationResult turnResult = validateTestCase(
                    safeTurns.get(i),
                    schemaSplit.perTurn(),
                    effectiveTemplate,
                    effectiveBindings,
                    hasOverrides,
                    datasetId);
            for (ValidationWarningDto w : turnResult.getWarnings()) {
                w.setTurnIndex(i);
                warnings.add(w);
            }
        }

        boolean valid = warnings.isEmpty();

        int maxWarnings = validationProperties.getMaxWarningsPerCase();
        if (warnings.size() > maxWarnings) {
            warnings.subList(maxWarnings, warnings.size()).clear();
        }

        return ValidationResult.builder()
                .valid(valid)
                .warnings(List.copyOf(warnings))
                .build();
    }

    private void validateFileFields(
            Map<String, Object> data,
            List<FieldDefinitionDto> schema,
            UUID datasetId,
            List<ValidationWarningDto> warnings) {
        for (FieldDefinitionDto field : schema) {
            if (field == null || field.getType() != SchemaFieldType.FILE || field.getName() == null) {
                continue;
            }
            Object value = data.get(field.getName());
            if (value == null || value.toString().isBlank()) {
                continue; // null or blank FILE value is OK (handled by required-field check above)
            }
            String valueStr = value.toString();
            List<String> errors = fileRefValidator.validateDatasetOwnership(valueStr, datasetId);
            for (String error : errors) {
                warnings.add(warning(
                        field.getName(),
                        "$.data." + field.getName(),
                        "FILE field '" + field.getName() + "': " + error,
                        ValidationWarningCode.TYPE));
            }
        }
    }

    /**
     * Checks if a data value's Java type is compatible with the declared schema field type.
     * NUMBER accepts Integer, Long, and Double (an integer is a valid number).
     * INTEGER accepts both Integer and Long (Jackson deserializes small integers as Integer).
     */
    private static boolean isTypeCompatible(Object value, SchemaFieldType schemaType) {
        return switch (schemaType) {
            case STRING, FILE -> value instanceof String;
            case INTEGER -> value instanceof Number && !(value instanceof Double);
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> value instanceof Map;
            case ARRAY -> value instanceof List;
        };
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
