package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.service.FilePlaceholderDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared binding cross-validation logic used by both DEPLOYMENT and MCP_TOOL suite validation.
 * Checks: required variable without binding, binding to unknown schema field,
 * orphan binding detection, {@code |file} constant-value validation, and {@code |file} hints bound to a
 * non-FILE schema field.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class BindingValidator {

    private final FileRefValidator fileRefValidator;
    private final FilePlaceholderDetector filePlaceholderDetector;

    /**
     * Validates template-variable-to-binding and binding-to-template cross-references.
     *
     * @param variables  extracted template variables
     * @param bindings   input bindings (may be null or empty)
     * @param schema     test case schema field definitions (may be null or empty)
     * @param suiteId    existing suite UUID for ownership validation; null on create
     * @param pathPrefix warning-path prefix — {@code ""} for request #0 (preserves the pre-chain
     *                   literal {@code "$.inputBindings"} byte-for-byte); {@code "$.additionalRequests[i]"}
     *                   for an additional request, yielding {@code "$.additionalRequests[i].inputBindings"}
     * @return list of validation warnings
     */
    public List<ValidationWarningDto> validate(
            List<TemplateVariableExtractor.ExtractedVariable> variables,
            List<InputBindingDto> bindings,
            List<FieldDefinitionDto> schema,
            UUID suiteId,
            String pathPrefix) {
        List<ValidationWarningDto> warnings = new ArrayList<>();
        List<InputBindingDto> effectiveBindings = bindings != null ? bindings : List.of();
        List<FieldDefinitionDto> effectiveSchema = schema != null ? schema : List.of();
        String path = pathPrefix.isEmpty() ? "$.inputBindings" : pathPrefix + ".inputBindings";

        // Build binding lookup
        Map<String, InputBindingDto> bindingByVar = effectiveBindings.stream()
                .filter(b -> b != null && b.getTemplateVariable() != null)
                .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));

        // Build schema field name set
        Set<String> schemaFieldNames = effectiveSchema.stream()
                .filter(f -> f != null && f.getName() != null)
                .map(FieldDefinitionDto::getName)
                .collect(Collectors.toSet());

        // Build variable name set for orphan detection
        Set<String> variableNames = variables.stream()
                .map(TemplateVariableExtractor.ExtractedVariable::getName)
                .collect(Collectors.toSet());

        // Template → Binding validation
        for (TemplateVariableExtractor.ExtractedVariable var : variables) {
            InputBindingDto binding = bindingByVar.get(var.getName());
            if (binding == null && !var.isHasDefault()) {
                warnings.add(warning(
                        var.getName(),
                        path,
                        "Required variable '" + var.getName() + "' has no binding",
                        ValidationWarningCode.REQUIRED));
            }
            if (binding != null
                    && binding.getDataField() != null
                    && !binding.getDataField().isBlank()
                    && !schemaFieldNames.contains(binding.getDataField())) {
                warnings.add(warning(
                        binding.getDataField(),
                        path,
                        "Binding maps variable '" + var.getName() + "' to unknown field '" + binding.getDataField()
                                + "'",
                        ValidationWarningCode.UNKNOWN));
            }
            // Validate constantValue for |file typed variables
            if (binding != null
                    && binding.getConstantValue() != null
                    && var.getDeclaredType() == SchemaFieldType.FILE) {
                String refValue = binding.getConstantValue().toString();
                List<String> errors = fileRefValidator.validateSuiteOwnership(refValue, suiteId);
                for (String error : errors) {
                    warnings.add(warning(
                            var.getName(),
                            path,
                            "Constant binding for '|file' variable '" + var.getName() + "': " + error,
                            ValidationWarningCode.TYPE));
                }
                if (errors.isEmpty() && fileRefValidator.isDatasetShapedRef(refValue)) {
                    warnings.add(warning(
                            var.getName(),
                            path,
                            "Constant binding for '|file' variable '" + var.getName()
                                    + "' references dataset-scoped file '" + refValue
                                    + "'; suite-level fields must use @ef/suites/ refs",
                            ValidationWarningCode.TYPE));
                }
            }
            addFileHintColumnMismatchWarning(var, binding, effectiveSchema, path, warnings);
        }

        // Binding → Template validation (orphan bindings)
        for (InputBindingDto binding : effectiveBindings) {
            if (binding == null || binding.getTemplateVariable() == null) {
                continue;
            }
            if (!variableNames.contains(binding.getTemplateVariable())) {
                warnings.add(warning(
                        binding.getTemplateVariable(),
                        path,
                        "Binding for '" + binding.getTemplateVariable() + "' but no ${{" + binding.getTemplateVariable()
                                + "}} in template",
                        ValidationWarningCode.ADDITIONAL));
            }
        }

        return warnings;
    }

    /**
     * A {@code |file} hint on a variable bound via {@code dataField} to a schema field of another type would
     * push a non-reference value through the file resolver; a hintless placeholder bound to a FILE field
     * needs no hint (it resolves by column type) and is not flagged.
     */
    private void addFileHintColumnMismatchWarning(
            TemplateVariableExtractor.ExtractedVariable var,
            InputBindingDto binding,
            List<FieldDefinitionDto> schema,
            String path,
            List<ValidationWarningDto> warnings) {
        if (var.getDeclaredType() != SchemaFieldType.FILE) {
            return;
        }
        filePlaceholderDetector
                .boundFieldType(binding, schema)
                .filter(columnType -> columnType != SchemaFieldType.FILE)
                .ifPresent(columnType -> warnings.add(warning(
                        var.getName(),
                        path,
                        "Variable '" + var.getName() + "' has a '|file' type hint but is bound to field '"
                                + binding.getDataField() + "' of type " + columnType
                                + "; bind a FILE field or remove the hint",
                        ValidationWarningCode.TYPE)));
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
