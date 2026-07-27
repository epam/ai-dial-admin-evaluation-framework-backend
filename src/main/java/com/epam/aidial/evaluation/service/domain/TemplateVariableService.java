package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extracts template variables from a suite's requestTemplate, resolves bindings,
 * infers types (priority: declared > endpointRef schema > dataset testCaseSchema > STRING),
 * and populates resolved values. The test-case schema is sourced from the suite's referenced dataset.
 *
 * <p>The test-case entry point ({@link #getTestCaseTemplateVariables(UUID, UUID)}) additionally
 * resolves each variable's {@code resolvedValue} against that test case's dataset-owned {@code data};
 * it is otherwise identical to the suite-level {@link #getTemplateVariables(UUID)}.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class TemplateVariableService {

    private final TestSuiteRepository testSuiteRepository;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TemplateVariableExtractor templateVariableExtractor;
    private final EndpointSchemaExtractor endpointSchemaExtractor;
    private final JsonbMapper jsonbMapper;
    private final TemplateVariableResolver templateVariableResolver;
    private final TestCaseService testCaseService;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<TemplateVariableDto> getTemplateVariables(UUID testSuiteId) {
        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));

        // Unbound suite (datasetId == null) carries no schema — return empty so binding
        // cross-checks see "no fields available" instead of throwing on the null id.
        List<FieldDefinitionDto> testCaseSchema =
                suite.getDatasetId() != null ? datasetSchemaProvider.getSchema(suite.getDatasetId()) : List.of();

        if (suite.getSuiteType() == SuiteType.MCP_TOOL) {
            ArgumentTemplateDto argumentTemplate = jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate());
            List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
            return resolveMcpVariables(argumentTemplate, bindings, testCaseSchema, null);
        }

        RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
        List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
        EndpointContractDto endpoint = jsonbMapper.mapEndpointContract(suite.getEndpointRef());

        return resolveVariables(template, bindings, testCaseSchema, endpoint, null);
    }

    /**
     * Returns template variables for a specific test case: the suite's template and bindings resolved
     * against that test case's {@code data}. The test case is looked up dataset-scoped via the suite's
     * {@code datasetId}; per-test-case overrides were removed when test cases moved to datasets, so the
     * suite is the single source of truth and the only difference from {@link #getTemplateVariables(UUID)}
     * is that {@code resolvedValue} is resolved from the test case's data.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<TemplateVariableDto> getTestCaseTemplateVariables(UUID testSuiteId, UUID testCaseId) {
        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));

        // An unbound suite (datasetId == null) owns no test cases — return 404. This guard is required:
        // TestCaseService.getById delegates to findByIdAndDatasetId, which NPEs on a null datasetId.
        if (suite.getDatasetId() == null) {
            throw new EntityNotFoundException("TestCase not found: " + testCaseId);
        }

        TestCaseResponseDto testCase = testCaseService.getById(suite.getDatasetId(), testCaseId, false);
        Map<String, Object> data = testCase.getData();

        List<FieldDefinitionDto> testCaseSchema = datasetSchemaProvider.getSchema(suite.getDatasetId());

        if (suite.getSuiteType() == SuiteType.MCP_TOOL) {
            ArgumentTemplateDto argumentTemplate = jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate());
            List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
            return resolveMcpVariables(argumentTemplate, bindings, testCaseSchema, data);
        }

        RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
        List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
        EndpointContractDto endpoint = jsonbMapper.mapEndpointContract(suite.getEndpointRef());

        return resolveVariables(template, bindings, testCaseSchema, endpoint, data);
    }

    /**
     * Resolves MCP argument template variables with optional input bindings.
     * Resolution priority per variable: binding constantValue > binding dataField lookup >
     * direct variable name lookup > template default > null.
     * Type is inferred from testCaseSchema by variable name, with STRING as fallback.
     */
    private List<TemplateVariableDto> resolveMcpVariables(
            ArgumentTemplateDto argumentTemplate,
            List<InputBindingDto> bindings,
            List<FieldDefinitionDto> testCaseSchema,
            Map<String, Object> data) {
        List<TemplateVariableExtractor.ExtractedVariable> extracted =
                templateVariableExtractor.extractFromArgumentTemplate(argumentTemplate);
        if (extracted.isEmpty()) {
            return List.of();
        }

        Map<String, SchemaFieldType> schemaTypeByName = (testCaseSchema != null
                        ? testCaseSchema
                        : List.<FieldDefinitionDto>of())
                .stream()
                        .filter(f -> f != null && f.getName() != null)
                        .collect(Collectors.toMap(
                                FieldDefinitionDto::getName, FieldDefinitionDto::getType, (a, b) -> a));

        Map<String, InputBindingDto> bindingByVar = (bindings != null ? bindings : List.<InputBindingDto>of())
                .stream()
                        .filter(b -> b != null && b.getTemplateVariable() != null)
                        .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));

        return extracted.stream()
                .map(var -> {
                    SchemaFieldType effectiveType = var.getDeclaredType() != null
                            ? var.getDeclaredType()
                            : schemaTypeByName.getOrDefault(var.getName(), SchemaFieldType.STRING);

                    InputBindingDto binding = bindingByVar.get(var.getName());
                    Object resolvedValue = resolveMcpVariableValue(var, binding, data);

                    return TemplateVariableDto.builder()
                            .name(var.getName())
                            .sources(var.getSources())
                            .hasDefault(var.isHasDefault())
                            .defaultValue(var.getDefaultValue())
                            .binding(binding)
                            .declaredType(var.getDeclaredType())
                            .effectiveType(effectiveType)
                            .resolvedValue(resolvedValue)
                            .build();
                })
                .toList();
    }

    private Object resolveMcpVariableValue(
            TemplateVariableExtractor.ExtractedVariable var, InputBindingDto binding, Map<String, Object> data) {
        if (binding != null) {
            if (binding.getConstantValue() != null) {
                return binding.getConstantValue();
            }
            if (binding.getDataField() != null
                    && !binding.getDataField().isBlank()
                    && data != null
                    && data.containsKey(binding.getDataField())) {
                return data.get(binding.getDataField());
            }
        }
        // No binding — fall back to direct variable name lookup
        if (data != null && data.containsKey(var.getName())) {
            return data.get(var.getName());
        }
        if (var.isHasDefault()) {
            return var.getDefaultValue();
        }
        return null;
    }

    /**
     * Resolves template variables with bindings, type inference, and resolved values.
     *
     * @param data test case data map — nullable; when null (suite-level),
     *             data-field bindings resolve to null (fall through to default)
     */
    List<TemplateVariableDto> resolveVariables(
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            List<FieldDefinitionDto> testCaseSchema,
            EndpointContractDto endpoint,
            Map<String, Object> data) {
        List<TemplateVariableExtractor.ExtractedVariable> extracted = templateVariableExtractor.extract(template);
        if (extracted.isEmpty()) {
            return List.of();
        }

        Map<String, InputBindingDto> bindingByVar = (bindings != null ? bindings : List.<InputBindingDto>of())
                .stream()
                        .filter(b -> b != null && b.getTemplateVariable() != null)
                        .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));

        Map<String, SchemaFieldType> schemaTypeByName = (testCaseSchema != null
                        ? testCaseSchema
                        : List.<FieldDefinitionDto>of())
                .stream()
                        .filter(f -> f != null && f.getName() != null)
                        .collect(Collectors.toMap(
                                FieldDefinitionDto::getName, FieldDefinitionDto::getType, (a, b) -> a));

        // Endpoint schema field types (for type inference priority)
        List<FieldDefinitionDto> endpointFields = endpointSchemaExtractor.extractParameterFields(endpoint);
        Map<String, SchemaFieldType> endpointTypeByName = endpointFields.stream()
                .filter(f -> f != null && f.getName() != null)
                .collect(Collectors.toMap(FieldDefinitionDto::getName, FieldDefinitionDto::getType, (a, b) -> a));

        // Warnings accumulator for resolution (not exposed to caller — used only for side-effect-free resolution)
        List<ValidationWarningDto> warnings = new ArrayList<>();

        return extracted.stream()
                .map(var -> {
                    InputBindingDto binding = bindingByVar.get(var.getName());
                    SchemaFieldType effectiveType = inferType(
                            var.getName(), var.getDeclaredType(), binding, endpointTypeByName, schemaTypeByName);
                    Object resolvedValue = templateVariableResolver.resolveVariable(
                            var.getName(), var.getDefaultValue(), binding, ResolutionScope.ofData(data), warnings);

                    return TemplateVariableDto.builder()
                            .name(var.getName())
                            .sources(var.getSources())
                            .hasDefault(var.isHasDefault())
                            .defaultValue(var.getDefaultValue())
                            .binding(binding)
                            .declaredType(var.getDeclaredType())
                            .effectiveType(effectiveType)
                            .resolvedValue(resolvedValue)
                            .build();
                })
                .toList();
    }

    /**
     * Type inference priority: declared > endpointRef schema > dataset testCaseSchema (via binding's dataField) > STRING.
     */
    private static SchemaFieldType inferType(
            String varName,
            SchemaFieldType declaredType,
            InputBindingDto binding,
            Map<String, SchemaFieldType> endpointTypes,
            Map<String, SchemaFieldType> schemaTypes) {
        // 1. Declared type from placeholder syntax (highest priority)
        if (declaredType != null) {
            return declaredType;
        }

        // 2. Check endpoint schema by variable name
        SchemaFieldType endpointType = endpointTypes.get(varName);
        if (endpointType != null) {
            return endpointType;
        }

        // 3. Check testCaseSchema (sourced from suite's dataset) via binding's dataField
        if (binding != null
                && binding.getDataField() != null
                && !binding.getDataField().isBlank()) {
            SchemaFieldType schemaType = schemaTypes.get(binding.getDataField());
            if (schemaType != null) {
                return schemaType;
            }
        }

        // 4. Fallback
        return SchemaFieldType.STRING;
    }
}
