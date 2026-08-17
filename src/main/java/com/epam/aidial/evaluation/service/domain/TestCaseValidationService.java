package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
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
    private final TestCaseDataScopeResolver dataScopeResolver;

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
     * invalidating warning (not a 400). A multi-turn case with all-empty turn maps is valid when the
     * schema declares at least one per-turn field and no required per-turn field is unmet.
     *
     * <p>A case carrying turns while the schema declares <b>no</b> per-turn field at all is invalidated by
     * a second case-level warning: nothing can ever be stored in those turns, and the run path collapses
     * such a case to a single turn ({@code PerTurnBindingDetector} answers {@code false} without a per-turn
     * field), so the turns are dead weight. That warning is <b>prepended</b> to the warning list rather
     * than appended, so the {@code max-warnings-per-case} truncation below cannot drop the one warning that
     * explains a pile of per-field ones. Both case-level warnings are emitted when both apply.
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

        final TestCaseDataScopeResolver.ScopePlacement placement =
                dataScopeResolver.inspect(sharedData, safeTurns, testCaseSchema);
        final List<FieldDefinitionDto> sharedSchema =
                withMisplacedRequiredCleared(schemaSplit.shared(), placement.misplacedFields());
        final List<FieldDefinitionDto> perTurnSchema =
                withMisplacedRequiredCleared(schemaSplit.perTurn(), placement.misplacedFields());

        // Shared (test-case-level) fields are validated once against the shared sub-schema; their warnings
        // carry no turn index.
        ValidationResult sharedResult = validateTestCase(
                placement.shared(), sharedSchema, effectiveTemplate, effectiveBindings, hasOverrides, datasetId);

        List<ValidationWarningDto> warnings = new ArrayList<>(placement.warnings());
        warnings.addAll(sharedResult.getWarnings());

        int maxTurns = testCaseProperties.getMultiTurn().getMaxTurns();
        if (safeTurns.size() > maxTurns) {
            warnings.add(warning(
                    null,
                    "$.multiTurnData",
                    "Multi-turn case has " + safeTurns.size() + " turns, exceeding the maximum of " + maxTurns,
                    ValidationWarningCode.ADDITIONAL));
        }

        if (!safeTurns.isEmpty() && !declaresPerTurnColumn(schemaSplit.perTurn())) {
            warnings.add(
                    0,
                    warning(
                            null,
                            "$.multiTurnData",
                            "Test case has " + safeTurns.size()
                                    + " turns but the dataset schema declares no per-turn columns; turn data cannot be"
                                    + " attached",
                            ValidationWarningCode.ADDITIONAL));
        }

        final List<Map<String, Object>> placementTurns = placement.turns();
        for (int i = 0; i < placementTurns.size(); i++) {
            ValidationResult turnResult = validateTestCase(
                    placementTurns.get(i),
                    perTurnSchema,
                    effectiveTemplate,
                    effectiveBindings,
                    hasOverrides,
                    datasetId);
            for (ValidationWarningDto w : turnResult.getWarnings()) {
                stampTurnWarning(w, i);
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

    /**
     * Whether {@code perTurnSchema} holds at least one usable per-turn column — a field with a non-blank
     * name. The name filter matches {@link TestCaseFieldScopeResolver#perTurnFieldNames}, so a malformed
     * schema entry ({@code perTurn=true} with a null/blank name, which no data key can ever match) does not
     * pass for a real per-turn column and silently suppress the no-per-turn-columns warning.
     */
    private static boolean declaresPerTurnColumn(List<FieldDefinitionDto> perTurnSchema) {
        return perTurnSchema.stream()
                .anyMatch(field -> field != null
                        && field.getName() != null
                        && !field.getName().isBlank());
    }

    /**
     * Returns a copy of {@code schema} where every field named in {@code misplacedFields} has its {@code
     * required} flag cleared, so the required-missing check ({@code validateTestCase}, ~line 148) does not
     * fire for a field that was never meant to live in this bucket. The field definition itself — name,
     * type, {@code perTurn} — is kept intact, so a value legitimately placed in this bucket under the same
     * name is still recognized as known and still type-checked; only the misplaced occurrence's
     * contradictory "missing" signal is neutralized. Fields not in {@code misplacedFields} are returned
     * unchanged (same instance).
     */
    private static List<FieldDefinitionDto> withMisplacedRequiredCleared(
            List<FieldDefinitionDto> schema, Set<String> misplacedFields) {
        if (misplacedFields.isEmpty()) {
            return schema;
        }
        return schema.stream()
                .map(field -> field != null && field.getName() != null && misplacedFields.contains(field.getName())
                        ? clearRequired(field)
                        : field)
                .toList();
    }

    /** Returns a copy of {@code field} with {@code required=false}; does not mutate {@code field}. */
    private static FieldDefinitionDto clearRequired(FieldDefinitionDto field) {
        return FieldDefinitionDto.builder()
                .name(field.getName())
                .displayName(field.getDisplayName())
                .type(field.getType())
                .required(false)
                .description(field.getDescription())
                .perTurn(field.getPerTurn())
                .build();
    }

    /**
     * Stamps a per-turn warning with its {@code turnIndex} and rewrites its {@code path} from
     * {@code $.data.<field>} to {@code $.multiTurnData[<turnIndex>].<field>}, so the path identifies the
     * bucket the warning came from. A warning whose path is {@code null} or does not start with
     * {@code $.data.} (e.g. an override-binding warning at {@code $.inputBindingsOverride}) is left with
     * its original path — it is not bucket-specific, so there is nothing to rewrite.
     */
    private static void stampTurnWarning(ValidationWarningDto warning, int turnIndex) {
        warning.setTurnIndex(turnIndex);
        final String path = warning.getPath();
        final String dataPrefix = "$.data.";
        if (path != null && path.startsWith(dataPrefix)) {
            warning.setPath("$.multiTurnData[" + turnIndex + "]." + path.substring(dataPrefix.length()));
        }
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
