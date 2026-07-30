package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Suite-level validation: checks template + binding configuration correctness.
 * Results are stored in TestSuite.isValid + TestSuite.validationWarnings.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class SuiteValidationService {

    private final TemplateVariableExtractor templateVariableExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final FileRefValidator fileRefValidator;
    private final BindingValidator bindingValidator;
    private final JsonbMapper jsonbMapper;

    /**
     * Validates suite configuration: requestTemplate, inputBindings, testCaseSchema.
     * Produces warnings per the Validation Matrix (design.md).
     *
     * @param dto             suite request DTO
     * @param suiteId         existing suite UUID for ownership validation; null on create (ownership check skipped)
     * @param testCaseSchema  dataset-resolved test-case schema (the suite no longer owns it).
     *                        Pass an empty list if the caller has no schema to bind against.
     */
    public ValidationResult validateSuite(
            TestSuiteRequestDto dto, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        SuiteType suiteType = dto.getSuiteType() != null ? dto.getSuiteType() : SuiteType.DEPLOYMENT;
        if (suiteType == SuiteType.MCP_TOOL) {
            return validateMcpSuite(dto, suiteId, testCaseSchema);
        }
        return validateDeploymentSuite(dto, suiteId, testCaseSchema);
    }

    /**
     * Model-based overload used by Phase 2 of dataset-rooted revalidation
     * ({@link RevalidationService#runDatasetRevalidationAsync}). Reverse-maps the JSONB-backed
     * {@link TestSuite} columns into a transient {@link TestSuiteRequestDto} and forwards the
     * dataset's typed schema (since the suite no longer owns {@code testCaseSchema}).
     *
     * @param suite             persistent suite entity
     * @param testCaseSchema    dataset's resolved schema (parameter so the dataset table is queried once
     *                          by the caller and reused across the suite fan-out)
     */
    public ValidationResult validateSuite(TestSuite suite, List<FieldDefinitionDto> testCaseSchema) {
        TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .name(suite.getName())
                .description(suite.getDescription())
                .suiteType(suite.getSuiteType())
                .datasetId(suite.getDatasetId())
                .deploymentRef(jsonbMapper.map(suite.getDeploymentRef()))
                .endpointRef(jsonbMapper.mapEndpointContract(suite.getEndpointRef()))
                .responseColumns(jsonbMapper.mapResponseColumns(suite.getResponseColumns()))
                .requestTemplate(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate()))
                .inputBindings(jsonbMapper.mapInputBindings(suite.getInputBindings()))
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef()))
                .toolRef(jsonbMapper.mapToolRef(suite.getToolRef()))
                .argumentTemplate(jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate()))
                .build();
        return validateSuite(dto, suite.getId(), testCaseSchema);
    }

    private ValidationResult validateMcpSuite(
            TestSuiteRequestDto dto, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        if (dto.getArgumentTemplate() == null) {
            warnings.add(warning(
                    null,
                    "$.argumentTemplate",
                    "argumentTemplate is recommended for MCP tool invocation",
                    ValidationWarningCode.ADDITIONAL));
        } else {
            // Extract variables from argument template (with type-hint warnings)
            TemplateVariableExtractor.ExtractionResult extractionResult =
                    templateVariableExtractor.extractFromArgumentTemplateWithWarnings(dto.getArgumentTemplate());
            List<TemplateVariableExtractor.ExtractedVariable> variables = extractionResult.getVariables();

            // Add warnings for unrecognised type hints
            for (String typeHintWarning : extractionResult.getTypeHintWarnings()) {
                warnings.add(warning(null, "$.argumentTemplate", typeHintWarning, ValidationWarningCode.TYPE));
            }

            // Shared binding cross-validation
            List<InputBindingDto> bindings = dto.getInputBindings();
            warnings.addAll(bindingValidator.validate(variables, bindings, testCaseSchema, suiteId));
        }

        return ValidationResult.builder()
                .valid(warnings.isEmpty())
                .warnings(warnings)
                .build();
    }

    private ValidationResult validateDeploymentSuite(
            TestSuiteRequestDto dto, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        // endpointRef is soft-validated: null → isValid=false with warning, not HTTP 400
        if (dto.getEndpointRef() == null) {
            warnings.add(warning(
                    null,
                    "$.endpointRef",
                    "endpointRef is required for request assembly",
                    ValidationWarningCode.REQUIRED));
        }

        RequestTemplateDto template = dto.getRequestTemplate();
        List<InputBindingDto> bindings = dto.getInputBindings();

        // URL template validation
        if (template == null) {
            warnings.add(warning(
                    null, "$", "requestTemplate is required for request assembly", ValidationWarningCode.REQUIRED));
        } else if (template.getUrlTemplate() == null
                || template.getUrlTemplate().isBlank()) {
            warnings.add(warning(
                    null,
                    "$.urlTemplate",
                    "urlTemplate is required for request assembly",
                    ValidationWarningCode.REQUIRED));
        }

        // Extract variables from template (with type-hint warnings)
        TemplateVariableExtractor.ExtractionResult extractionResult =
                templateVariableExtractor.extractWithWarnings(template);
        List<TemplateVariableExtractor.ExtractedVariable> variables = extractionResult.getVariables();

        // Add warnings for unrecognised type hints
        for (String typeHintWarning : extractionResult.getTypeHintWarnings()) {
            warnings.add(warning(null, "$.requestTemplate", typeHintWarning, ValidationWarningCode.TYPE));
        }

        // Shared binding cross-validation
        warnings.addAll(bindingValidator.validate(variables, bindings, testCaseSchema, suiteId));

        // Multipart FILE part constant value validation (deployment-specific)
        if (template != null
                && template.getBody() instanceof MultipartFormDataRequestBodyDto multipartBody
                && multipartBody.getContent() != null) {
            for (var part : multipartBody.getContent()) {
                if (part == null || part.getType() != FormPartType.FILE || part.getValue() == null) {
                    continue;
                }
                String refValue = part.getValue().toString();
                // Skip file ref validation for template variable placeholders
                if (templateVariableExtractor.isPlaceholder(refValue)) {
                    continue;
                }
                List<String> errors = fileRefValidator.validateSuiteOwnership(refValue, suiteId);
                for (String error : errors) {
                    warnings.add(warning(
                            part.getName(),
                            "$.requestTemplate.body",
                            "FILE form part '" + part.getName() + "': " + error,
                            ValidationWarningCode.TYPE));
                }
                if (errors.isEmpty() && fileRefValidator.isDatasetShapedRef(refValue)) {
                    warnings.add(warning(
                            part.getName(),
                            "$.requestTemplate.body",
                            "FILE form part '" + part.getName() + "' references dataset-scoped file '" + refValue
                                    + "'; suite-level fields must use @ef/suites/ refs",
                            ValidationWarningCode.TYPE));
                }
            }
        }

        // Content-type mismatch: template body vs endpoint schema (deployment-specific)
        if (template != null && template.getBody() != null) {
            EndpointContractDto endpoint = dto.getEndpointRef();
            if (endpoint != null && endpoint.getRequestBodySchema() != null) {
                RequestBodyDto body = template.getBody();
                RequestBodySchemaDto schemaDto = endpoint.getRequestBodySchema();
                if (!body.getContentType().equals(schemaDto.getContentType())) {
                    warnings.add(warning(
                            null,
                            "$.requestTemplate.body",
                            "Request template content type '" + body.getContentType()
                                    + "' does not match endpoint schema content type '"
                                    + schemaDto.getContentType() + "'",
                            ValidationWarningCode.TYPE));
                }
            }
        }

        // Header blacklist validation (deployment-specific)
        if (template != null && template.getHeaders() != null) {
            List<String> blacklist = evaluationRunProperties.getExecution().getHeaderBlacklist();
            Set<String> blacklistLower =
                    blacklist.stream().map(String::toLowerCase).collect(Collectors.toSet());

            for (KeyValueTemplateDto header : template.getHeaders()) {
                if (header != null
                        && header.getKey() != null
                        && blacklistLower.contains(header.getKey().toLowerCase())) {
                    warnings.add(warning(
                            header.getKey(),
                            "$.requestTemplate.headers",
                            "Header '" + header.getKey() + "' is system-managed and cannot be set in request template",
                            ValidationWarningCode.ADDITIONAL));
                }
            }
        }

        return ValidationResult.builder()
                .valid(warnings.isEmpty())
                .warnings(warnings)
                .build();
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
