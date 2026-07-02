package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class MetricDefinitionValidationService {

    private final ObjectMapper objectMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;
    private final JsonataEvaluationService jsonataEvaluationService;

    /**
     * Validates config and input bindings against their respective metric schemas and the suite context.
     *
     * <p>Applies five checks for each binding list × schema pair:
     * <ol>
     *   <li>ADDITIONAL — property not in schema "properties"</li>
     *   <li>REQUIRED (null-constant) — ConstantBindingSource.value is null for a required property</li>
     *   <li>UNRESOLVED_REFERENCE (testcase) — TestCaseBindingSource.columnName not in testCaseSchema</li>
     *   <li>UNRESOLVED_REFERENCE (response) — ResponseBindingSource.columnName not in responseColumns</li>
     *   <li>REQUIRED (no-binding) — required property in schema has no binding at all</li>
     * </ol>
     *
     * @param configBindings       bindings for metric config schema (may be null/empty)
     * @param inputBindings        bindings for metric input schema (may be null/empty)
     * @param configSchemaJson     JSON text of config schema (JSONB from MetricDeclarationVersion)
     * @param inputSchemaJson      JSON text of input schema (JSONB from MetricDeclarationVersion)
     * @param testCaseSchemaJson   JSON text of test case schema (JSONB from TestSuite)
     * @param responseColumnsJson  JSON text of response columns (JSONB from TestSuite)
     * @param outputSchemaJson     JSON text of output schema (JSONB from MetricDeclarationVersion)
     * @return validation result with valid flag and list of warnings
     */
    public ValidationResult validate(
            List<MetricParameterBindingDto> configBindings,
            List<MetricParameterBindingDto> inputBindings,
            String configSchemaJson,
            String inputSchemaJson,
            String testCaseSchemaJson,
            String responseColumnsJson,
            String outputSchemaJson) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        if (outputSchemaFieldExtractor.extractFieldNames(outputSchemaJson).isEmpty()) {
            warnings.add(ValidationWarningDto.builder()
                    .path("$.outputSchema")
                    .message("Metric output schema is missing, empty, or malformed")
                    .code(ValidationWarningCode.INVALID_OUTPUT_SCHEMA)
                    .build());
        }

        Set<String> testCaseColumns = extractTestCaseColumnNames(testCaseSchemaJson);
        Set<String> responseColumns = extractResponseColumnNames(responseColumnsJson);

        validateBindings(
                configBindings, configSchemaJson, testCaseColumns, responseColumns, "$.configBindings", warnings);
        validateBindings(inputBindings, inputSchemaJson, testCaseColumns, responseColumns, "$.inputBindings", warnings);

        return ValidationResult.builder()
                .valid(warnings.isEmpty())
                .warnings(warnings)
                .build();
    }

    private void validateBindings(
            List<MetricParameterBindingDto> bindings,
            String schemaJson,
            Set<String> testCaseColumns,
            Set<String> responseColumns,
            String bindingListPath,
            List<ValidationWarningDto> warnings) {
        if (bindings == null || bindings.isEmpty()) {
            // Still need to check required properties with no bindings
            Set<String> schemaProperties = extractSchemaPropertyNames(schemaJson);
            Set<String> requiredProperties = extractRequiredPropertyNames(schemaJson);
            for (String required : requiredProperties) {
                if (schemaProperties.contains(required)) {
                    warnings.add(buildWarning(
                            required,
                            ValidationWarningCode.REQUIRED,
                            null,
                            "Required property '" + required + "' has no binding"));
                }
            }
            return;
        }

        Set<String> schemaProperties = extractSchemaPropertyNames(schemaJson);
        Set<String> requiredProperties = extractRequiredPropertyNames(schemaJson);
        Set<String> boundProperties = new HashSet<>();

        for (MetricParameterBindingDto binding : bindings) {
            if (binding == null || binding.getProperty() == null) {
                continue;
            }
            String property = binding.getProperty();
            boundProperties.add(property);

            // Check 1: ADDITIONAL — property not in schema
            if (!schemaProperties.isEmpty() && !schemaProperties.contains(property)) {
                warnings.add(buildWarning(
                        property,
                        ValidationWarningCode.ADDITIONAL,
                        null,
                        "Property '" + property + "' is not defined in the metric schema"));
                continue;
            }

            if (binding.getSource() == null) {
                continue;
            }

            // Check 2: REQUIRED (null-constant) — only when schema has "properties" (graceful degradation)
            if (!schemaProperties.isEmpty() && binding.getSource() instanceof ConstantBindingSourceDto constantSource) {
                if (constantSource.getValue() == null && requiredProperties.contains(property)) {
                    warnings.add(buildWarning(
                            property,
                            ValidationWarningCode.REQUIRED,
                            null,
                            "Required property '" + property + "' is bound to a null constant value"));
                }
            }

            // Check 3: UNRESOLVED_REFERENCE (testcase)
            if (binding.getSource() instanceof TestCaseBindingSourceDto testCaseSource) {
                String columnName = testCaseSource.getColumnName();
                if (columnName != null && !testCaseColumns.contains(columnName)) {
                    warnings.add(buildWarning(
                            property,
                            ValidationWarningCode.UNRESOLVED_REFERENCE,
                            bindingListPath,
                            "TestCase column '" + columnName + "' does not exist in the suite's testCaseSchema"));
                }

                // Check 3b: INVALID_EXPRESSION — optional jsonataExpression must be syntactically valid JSONata
                String jsonataExpression = testCaseSource.getJsonataExpression();
                if (jsonataExpression != null && !jsonataExpression.isBlank()) {
                    try {
                        jsonataEvaluationService.validateExpression(jsonataExpression);
                    } catch (ValidationException e) {
                        warnings.add(buildWarning(
                                property,
                                ValidationWarningCode.INVALID_EXPRESSION,
                                bindingListPath,
                                "Invalid JSONata expression for test case binding: " + e.getMessage()));
                    }
                }
            }

            // Check 4: UNRESOLVED_REFERENCE (response)
            if (binding.getSource() instanceof ResponseBindingSourceDto responseSource) {
                String columnName = responseSource.getColumnName();
                if (columnName != null && !responseColumns.contains(columnName)) {
                    warnings.add(buildWarning(
                            property,
                            ValidationWarningCode.UNRESOLVED_REFERENCE,
                            bindingListPath,
                            "Response column '" + columnName + "' does not exist in the suite's responseColumns"));
                }

                // Check 4b: INVALID_EXPRESSION — optional jsonataExpression must be syntactically valid JSONata
                String jsonataExpression = responseSource.getJsonataExpression();
                if (jsonataExpression != null && !jsonataExpression.isBlank()) {
                    try {
                        jsonataEvaluationService.validateExpression(jsonataExpression);
                    } catch (ValidationException e) {
                        warnings.add(buildWarning(
                                property,
                                ValidationWarningCode.INVALID_EXPRESSION,
                                bindingListPath,
                                "Invalid JSONata expression for response binding: " + e.getMessage()));
                    }
                }
            }
        }

        // Check 5: REQUIRED — required property has no binding (only when schema has "properties")
        if (!schemaProperties.isEmpty()) {
            for (String required : requiredProperties) {
                if (!boundProperties.contains(required)) {
                    warnings.add(buildWarning(
                            required,
                            ValidationWarningCode.REQUIRED,
                            null,
                            "Required property '" + required + "' has no binding"));
                }
            }
        }
    }

    private Set<String> extractSchemaPropertyNames(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) {
                return Collections.emptySet();
            }
            Set<String> names = new HashSet<>();
            properties.propertyNames().forEach(names::add);
            return names;
        } catch (JacksonException e) {
            log.warn("Failed to parse metric schema JSON, skipping property checks: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    private Set<String> extractRequiredPropertyNames(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode required = schema.get("required");
            if (required == null || !required.isArray()) {
                return Collections.emptySet();
            }
            Set<String> names = new HashSet<>();
            required.forEach(node -> {
                if (node.isString()) {
                    names.add(node.asString());
                }
            });
            return names;
        } catch (JacksonException e) {
            log.warn("Failed to parse metric schema required array: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    private Set<String> extractTestCaseColumnNames(String testCaseSchemaJson) {
        if (testCaseSchemaJson == null || testCaseSchemaJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode schema = objectMapper.readTree(testCaseSchemaJson);
            if (!schema.isArray()) {
                return Collections.emptySet();
            }
            Set<String> names = new HashSet<>();
            schema.forEach(field -> {
                JsonNode nameNode = field.get("name");
                if (nameNode != null && nameNode.isString()) {
                    names.add(nameNode.asString());
                }
            });
            return names;
        } catch (JacksonException e) {
            log.warn("Failed to parse testCaseSchema JSON: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    private Set<String> extractResponseColumnNames(String responseColumnsJson) {
        if (responseColumnsJson == null || responseColumnsJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode schema = objectMapper.readTree(responseColumnsJson);
            if (!schema.isArray()) {
                return Collections.emptySet();
            }
            Set<String> names = new HashSet<>();
            schema.forEach(col -> {
                JsonNode nameNode = col.get("name");
                if (nameNode != null && nameNode.isString()) {
                    names.add(nameNode.asString());
                }
            });
            return names;
        } catch (JacksonException e) {
            log.warn("Failed to parse responseColumns JSON: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    private static ValidationWarningDto buildWarning(
            String property, ValidationWarningCode code, String path, String message) {
        return ValidationWarningDto.builder()
                .fieldName(property)
                .path(path)
                .message(message)
                .code(code)
                .build();
    }
}
