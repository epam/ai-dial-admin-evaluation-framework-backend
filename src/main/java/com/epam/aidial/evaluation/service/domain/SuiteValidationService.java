package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.McpSuite;
import com.epam.aidial.evaluation.runner.model.suite.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinition;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.service.RequestModelValidator;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
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
    private final McpArgumentValidator mcpArgumentValidator;
    private final SuiteDefinitionResolver suiteDefinitionResolver;
    private final RequestModelValidator requestModelValidator;

    /**
     * Validates suite configuration: requestTemplate, inputBindings, testCaseSchema.
     * Produces warnings per the Validation Matrix (design.md).
     *
     * <p>Derives the {@link SuiteDefinition} via {@link SuiteDefinitionResolver#fromRequest}; callers must
     * have run {@code TestSuiteRequestValidator.validateSuiteTypeFields} and {@code validateTestSuiteSchemas}
     * first (create, update and clone all do), so the derivation cannot throw and the chain has no null elements.
     *
     * @param dto             suite request DTO
     * @param suiteId         existing suite UUID for ownership validation; null on create (ownership check skipped)
     * @param testCaseSchema  dataset-resolved test-case schema (the suite no longer owns it).
     *                        Pass an empty list if the caller has no schema to bind against.
     */
    public ValidationResult validateSuite(
            TestSuiteRequestDto dto, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        return validate(suiteDefinitionResolver.fromRequest(dto), suiteId, testCaseSchema);
    }

    /**
     * Model-based overload used by Phase 2 of dataset-rooted revalidation
     * ({@link RevalidationService#runDatasetRevalidation}). Derives the {@link SuiteDefinition} straight from
     * the persisted {@link TestSuite} (no request-DTO round trip, so stored data is never re-rejected by the
     * write-time target checks) and forwards the dataset's typed schema (since the suite no longer owns
     * {@code testCaseSchema}).
     *
     * @param suite             persistent suite entity
     * @param testCaseSchema    dataset's resolved schema (parameter so the dataset table is queried once
     *                          by the caller and reused across the suite fan-out)
     */
    public ValidationResult validateSuite(TestSuite suite, List<FieldDefinitionDto> testCaseSchema) {
        return validate(suiteDefinitionResolver.fromEntity(suite), suite.getId(), testCaseSchema);
    }

    private ValidationResult validate(
            SuiteDefinition definition, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        return switch (definition) {
            case McpSuite m -> validateMcpSuite(m, suiteId, testCaseSchema);
            case HttpSuite h -> validateHttpSuite(h, suiteId, testCaseSchema);
        };
    }

    private ValidationResult validateMcpSuite(McpSuite suite, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        List<ValidationWarningDto> warnings = new ArrayList<>();
        final ArgumentTemplateDto argumentTemplate = suite.argumentTemplate();

        if (argumentTemplate == null) {
            warnings.add(warning(
                    null,
                    "$.argumentTemplate",
                    "argumentTemplate is recommended for MCP tool invocation",
                    ValidationWarningCode.ADDITIONAL));
        } else {
            // Extract variables from argument template (with type-hint warnings)
            TemplateVariableExtractor.ExtractionResult extractionResult =
                    templateVariableExtractor.extractFromArgumentTemplateWithWarnings(argumentTemplate);
            List<TemplateVariableExtractor.ExtractedVariable> variables = extractionResult.getVariables();

            // Add warnings for unrecognised type hints
            for (String typeHintWarning : extractionResult.getTypeHintWarnings()) {
                warnings.add(warning(null, "$.argumentTemplate", typeHintWarning, ValidationWarningCode.TYPE));
            }

            // Shared binding cross-validation. MCP_TOOL suites cannot carry additionalRequests
            // (rejected at write time), so the chain is always length 1 — prefix is always "".
            final List<InputBindingDto> bindings = suite.inputBindings();
            warnings.addAll(bindingValidator.validate(variables, bindings, testCaseSchema, suiteId, ""));

            // Argument coverage against the selected tool's own JSON schema
            final ToolReferenceDto toolRef = suite.target().tool();
            warnings.addAll(mcpArgumentValidator.validate(
                    toolRef != null ? toolRef.getInputSchema() : null, argumentTemplate.getArguments(), bindings));
        }

        return ValidationResult.builder()
                .valid(warnings.isEmpty())
                .warnings(warnings)
                .build();
    }

    private ValidationResult validateHttpSuite(HttpSuite suite, UUID suiteId, List<FieldDefinitionDto> testCaseSchema) {
        // Only DEPLOYMENT suites select the deployment by URL/body model; ROUTE suites have no deploymentRef.
        final DeploymentReferenceDto deploymentRef = suite.target() instanceof DeploymentTarget d ? d.ref() : null;

        List<ValidationWarningDto> warnings = new ArrayList<>();
        for (RequestExecutionSpec spec : suite.chain()) {
            // Request #0 keeps the unprefixed legacy paths; chain request i + 1 is additionalRequests[i].
            final String pathPrefix =
                    spec.requestIndex() == 0 ? "" : "$.additionalRequests[" + (spec.requestIndex() - 1) + "]";
            warnings.addAll(validateRequest(
                    spec.endpointRef(),
                    spec.requestTemplate(),
                    spec.inputBindings(),
                    suiteId,
                    testCaseSchema,
                    deploymentRef,
                    pathPrefix));
        }

        return ValidationResult.builder()
                .valid(warnings.isEmpty())
                .warnings(warnings)
                .build();
    }

    /**
     * Validates one request of the chain (request #0 or an additional request). {@code pathPrefix}
     * is {@code ""} for request #0, which keeps every one of its warning paths byte-identical to
     * before request chains existed (design.md D13) — e.g. {@code "$.urlTemplate"},
     * {@code "$.requestTemplate.body"}, {@code "$.requestTemplate.headers"}, {@code "$.endpointRef"}.
     * An additional request passes {@code "$.additionalRequests[i]"}, yielding the indexed form
     * {@code "$.additionalRequests[i].requestTemplate.urlTemplate"} etc.
     */
    private List<ValidationWarningDto> validateRequest(
            EndpointContractDto endpoint,
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            UUID suiteId,
            List<FieldDefinitionDto> testCaseSchema,
            DeploymentReferenceDto deploymentRef,
            String pathPrefix) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        // endpointRef is soft-validated: null → isValid=false with warning, not HTTP 400
        if (endpoint == null) {
            warnings.add(warning(
                    null,
                    warningPath(pathPrefix, "$.endpointRef", ".endpointRef"),
                    "endpointRef is required for request assembly",
                    ValidationWarningCode.REQUIRED));
        }

        // URL template validation
        if (template == null) {
            warnings.add(warning(
                    null,
                    warningPath(pathPrefix, "$", ".requestTemplate"),
                    "requestTemplate is required for request assembly",
                    ValidationWarningCode.REQUIRED));
        } else if (template.getUrlTemplate() == null
                || template.getUrlTemplate().isBlank()) {
            warnings.add(warning(
                    null,
                    warningPath(pathPrefix, "$.urlTemplate", ".requestTemplate.urlTemplate"),
                    "urlTemplate is required for request assembly",
                    ValidationWarningCode.REQUIRED));
        }

        // Extract variables from template (with type-hint warnings)
        TemplateVariableExtractor.ExtractionResult extractionResult =
                templateVariableExtractor.extractWithWarnings(template);
        List<TemplateVariableExtractor.ExtractedVariable> variables = extractionResult.getVariables();

        // Add warnings for unrecognised type hints
        for (String typeHintWarning : extractionResult.getTypeHintWarnings()) {
            warnings.add(warning(
                    null,
                    warningPath(pathPrefix, "$.requestTemplate", ".requestTemplate"),
                    typeHintWarning,
                    ValidationWarningCode.TYPE));
        }

        // Shared binding cross-validation
        warnings.addAll(bindingValidator.validate(variables, bindings, testCaseSchema, suiteId, pathPrefix));

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
                            warningPath(pathPrefix, "$.requestTemplate.body", ".requestTemplate.body"),
                            "FILE form part '" + part.getName() + "': " + error,
                            ValidationWarningCode.TYPE));
                }
                if (errors.isEmpty() && fileRefValidator.isDatasetShapedRef(refValue)) {
                    warnings.add(warning(
                            part.getName(),
                            warningPath(pathPrefix, "$.requestTemplate.body", ".requestTemplate.body"),
                            "FILE form part '" + part.getName() + "' references dataset-scoped file '" + refValue
                                    + "'; suite-level fields must use @ef/suites/ refs",
                            ValidationWarningCode.TYPE));
                }
            }
        }

        // Content-type mismatch: template body vs endpoint schema (deployment-specific)
        if (template != null
                && template.getBody() != null
                && endpoint != null
                && endpoint.getRequestBodySchema() != null) {
            RequestBodyDto body = template.getBody();
            RequestBodySchemaDto schemaDto = endpoint.getRequestBodySchema();
            if (!body.getContentType().equals(schemaDto.getContentType())) {
                warnings.add(warning(
                        null,
                        warningPath(pathPrefix, "$.requestTemplate.body", ".requestTemplate.body"),
                        "Request template content type '" + body.getContentType()
                                + "' does not match endpoint schema content type '" + schemaDto.getContentType()
                                + "'",
                        ValidationWarningCode.TYPE));
            }
        }

        warnings.addAll(validateFixedPathRequestModel(endpoint, template, deploymentRef, pathPrefix));

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
                            warningPath(pathPrefix, "$.requestTemplate.headers", ".requestTemplate.headers"),
                            "Header '" + header.getKey() + "' is system-managed and cannot be set in request template",
                            ValidationWarningCode.ADDITIONAL));
                }
            }
        }

        return warnings;
    }

    /**
     * Static plain-{@code content} model check for the fixed-path, model-selecting APIs
     * ({@code /openai/v1/responses}, {@code /anthropic/v1/messages}), where the body's {@code model}
     * field — not the URL — selects the deployment. {@code jsonataContent} bodies are deliberately
     * deferred to the run-time check in {@code TurnLoopExecutor}; their syntax validation is unaffected.
     */
    private List<ValidationWarningDto> validateFixedPathRequestModel(
            EndpointContractDto endpoint,
            RequestTemplateDto template,
            DeploymentReferenceDto deploymentRef,
            String pathPrefix) {
        if (template == null
                || endpoint == null
                || deploymentRef == null
                || deploymentRef.getId() == null
                || !(template.getBody() instanceof JsonRequestBodyDto jsonBody)
                || jsonBody.getJsonataContent() != null) {
            return List.of();
        }
        return requestModelValidator
                .validateContent(endpoint.getRelativeUrlPattern(), jsonBody.getContent(), deploymentRef.getId())
                .map(message -> List.of(warning(
                        "model",
                        warningPath(pathPrefix, "$.requestTemplate.body", ".requestTemplate.body"),
                        message,
                        ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR)))
                .orElseGet(List::of);
    }

    /**
     * Byte-identical to the pre-request-chain contract (design.md D13): request #0 ({@code pathPrefix == ""})
     * yields {@code rootPath} verbatim; any other request yields {@code pathPrefix + suffix}.
     */
    private static String warningPath(String pathPrefix, String rootPath, String suffix) {
        return pathPrefix.isEmpty() ? rootPath : pathPrefix + suffix;
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
