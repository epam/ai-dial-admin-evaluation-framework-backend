package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.constants.JsonataReservedNames;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RunnerValidationConstants;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.service.JsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataSourcePreprocessor;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRequestValidator {

    private final JsonataEvaluationService jsonataEvaluationService;
    private final JsonataSourcePreprocessor jsonataSourcePreprocessor;
    private final SchemaValidationService schemaValidationService;
    private final ObjectMapper objectMapper;
    private final ValidationProperties validationProperties;
    private final ResponseColumnUnionResolver responseColumnUnionResolver;

    /**
     * Validates type-specific hard-required fields (HTTP 400 if absent).
     * DEPLOYMENT: only deploymentRef is hard-required.
     * MCP_TOOL: mcpDeploymentRef and toolRef are hard-required.
     */
    public void validateSuiteTypeFields(TestSuiteRequestDto dto) {
        SuiteType suiteType = resolveSuiteType(dto);
        if (suiteType == SuiteType.DEPLOYMENT) {
            if (dto.getDeploymentRef() == null) {
                throw new ValidationException("deploymentRef is required for DEPLOYMENT suites");
            }
        } else if (suiteType == SuiteType.MCP_TOOL) {
            if (dto.getMcpDeploymentRef() == null) {
                throw new ValidationException("mcpDeploymentRef is required for MCP_TOOL suites");
            }
            if (dto.getToolRef() == null) {
                throw new ValidationException("toolRef is required for MCP_TOOL suites");
            }
            if (dto.getAdditionalRequests() != null
                    && !dto.getAdditionalRequests().isEmpty()) {
                throw new ValidationException("additionalRequests must be empty for MCP_TOOL suites");
            }
        }
    }

    /**
     * Validates embedded schemas on TestSuite create/update, for request #0 and, in chain order, for
     * every entry of {@code additionalRequests}. Throws ValidationException (400) if any schema is
     * malformed, if a response-column name repeats anywhere in the chain, or if the chain-wide
     * response-column union exceeds {@link RunnerValidationConstants#MAX_RESPONSE_COLUMNS}.
     *
     * <p>Request #0's messages are unprefixed (unchanged from before request chains existed); an
     * additional request's messages carry an {@code additionalRequests[i].} prefix, mirroring the
     * indexed warning-path convention used by soft validation ({@code SuiteValidationService}).
     */
    public void validateTestSuiteSchemas(TestSuiteRequestDto dto) {
        List<RequestDefinitionDto> additionalRequests = dto.getAdditionalRequests();
        validateNoNullAdditionalRequests(additionalRequests);

        validateEndpointSchemas(dto.getEndpointRef(), "");
        validateRequestTemplateBody(dto.getRequestTemplate(), "");

        if (additionalRequests != null) {
            for (int i = 0; i < additionalRequests.size(); i++) {
                RequestDefinitionDto request = additionalRequests.get(i);
                String prefix = additionalRequestPrefix(i);
                validateEndpointSchemas(request.getEndpointRef(), prefix);
                validateRequestTemplateBody(request.getRequestTemplate(), prefix);
            }
        }

        Set<String> seenNames = new HashSet<>();
        validateResponseColumns(dto.getResponseColumns(), "", seenNames);
        if (additionalRequests != null) {
            for (int i = 0; i < additionalRequests.size(); i++) {
                RequestDefinitionDto request = additionalRequests.get(i);
                validateResponseColumns(request.getResponseColumns(), additionalRequestPrefix(i), seenNames);
            }
        }

        int unionCount = responseColumnUnionResolver.unionFrom(dto).size();
        if (unionCount > RunnerValidationConstants.MAX_RESPONSE_COLUMNS) {
            throw new ValidationException("Response column union across the request chain (" + unionCount
                    + ") exceeds maximum of " + RunnerValidationConstants.MAX_RESPONSE_COLUMNS);
        }
    }

    /**
     * Rejects a null element anywhere in {@code additionalRequests} with a hard 400 naming the index.
     * Runs first in {@link #validateTestSuiteSchemas} — before any per-request field access — so this
     * is the single write-time gate every persistence path (create, update, and clone's effective-dto
     * revalidation) passes through; {@code RequestChainExecutor.buildSpecs} at run time therefore never
     * needs to defend against a null chain element (see the comment there).
     */
    private void validateNoNullAdditionalRequests(List<RequestDefinitionDto> additionalRequests) {
        if (additionalRequests == null) {
            return;
        }
        for (int i = 0; i < additionalRequests.size(); i++) {
            if (additionalRequests.get(i) == null) {
                throw new ValidationException("additionalRequests[" + i + "] must not be null");
            }
        }
    }

    private void validateEndpointSchemas(EndpointContractDto endpoint, String prefix) {
        if (endpoint == null) {
            return;
        }
        Optional<String> err = schemaValidationService.getSchemaValidationError(endpoint.getRequestBodySchema());
        if (err.isPresent()) {
            throw new ValidationException(prefix + "endpointRef.requestBodySchema: " + err.get());
        }
        err = schemaValidationService.getSchemaValidationError(endpoint.getResponseBodySchema());
        if (err.isPresent()) {
            throw new ValidationException(prefix + "endpointRef.responseBodySchema: " + err.get());
        }
        List<ParameterDefinitionDto> params = endpoint.getParameters();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                ParameterDefinitionDto p = params.get(i);
                if (p != null) {
                    Map<String, Object> paramSchema = p.getSchema();
                    err = schemaValidationService.getSchemaValidationError(paramSchema);
                    if (err.isPresent()) {
                        throw new ValidationException(
                                prefix + "endpointRef.parameters[" + i + "].schema: " + err.get());
                    }
                }
            }
        }
    }

    /**
     * Validates one request's response columns against the single {@code seenNames} set shared
     * across the whole chain (request #0 and every additional request), so a name repeated anywhere
     * in the chain is rejected regardless of which request declared it second.
     */
    private void validateResponseColumns(
            List<ResponseColumnDefinitionDto> responseColumns, String prefix, Set<String> seenNames) {
        if (responseColumns == null || responseColumns.isEmpty()) {
            return;
        }
        for (int i = 0; i < responseColumns.size(); i++) {
            ResponseColumnDefinitionDto col = responseColumns.get(i);
            if (col == null) {
                continue;
            }
            if (col.getName() == null || col.getName().isBlank()) {
                throw new ValidationException(prefix + "responseColumns[" + i + "]: name must not be blank");
            }
            if (!seenNames.add(col.getName())) {
                throw new ValidationException("responseColumns: duplicate column name '" + col.getName() + "' ("
                        + prefix + "responseColumns[" + i + "])");
            }
            if (JsonataReservedNames.RESERVED_COLUMN_NAMES.contains(col.getName())) {
                throw new ValidationException(prefix + "responseColumns[" + i + "] ('" + col.getName()
                        + "'): name is reserved (JSONata built-in function or frame variable)");
            }
            if (col.getExpression() == null || col.getExpression().isBlank()) {
                throw new ValidationException(prefix + "responseColumns[" + i + "]: expression must not be blank");
            }
            try {
                jsonataEvaluationService.validateExpression(col.getExpression());
            } catch (ValidationException ex) {
                throw new ValidationException(
                        prefix + "responseColumns[" + i + "] ('" + col.getName() + "').expression: " + ex.getMessage());
            }
        }
    }

    /**
     * Validates an {@code application/json} request body's two mutually exclusive carriers:
     * {@code content} ({@code Map<String, Object>}, legacy structural template — no JSONata
     * validation needed since a Map is validated at resolution time) and {@code jsonataContent}
     * ({@code String}, JSONata source — validated here as valid JSONata). Both non-null is
     * rejected; both null (or the body/template itself being null) means no request body and is
     * accepted without further checks.
     *
     * <p>A bare {@code ${{var}}} placeholder (e.g. {@code {"q": ${{question}}}}) is not, by itself,
     * valid JSONata — it only becomes valid once {@link JsonataSourcePreprocessor} substitutes it at
     * run time. Validating the raw source would therefore reject a well-formed bare-mode template.
     * {@link JsonataSourcePreprocessor#neutralize(String)} replaces every placeholder with a fixed
     * neutral token first (JSON {@code null} for quoted-full-value/bare, empty string for embedded),
     * so validation sees the same syntactic shape the runtime preprocessor produces, without needing
     * bindings or test-case data to be available yet.
     */
    private void validateRequestTemplateBody(RequestTemplateDto requestTemplate, String prefix) {
        if (requestTemplate == null) {
            return;
        }
        RequestBodyDto body = requestTemplate.getBody();
        if (!(body instanceof JsonRequestBodyDto jsonBody)) {
            return;
        }
        Map<String, Object> content = jsonBody.getContent();
        String jsonataContent = jsonBody.getJsonataContent();
        if (content != null && jsonataContent != null) {
            throw new ValidationException(
                    prefix + "requestTemplate.body: content and jsonataContent are mutually exclusive");
        }
        if (jsonataContent != null) {
            String neutralized = jsonataSourcePreprocessor.neutralize(jsonataContent);
            try {
                jsonataEvaluationService.validateExpression(neutralized);
            } catch (ValidationException ex) {
                throw new ValidationException(prefix + "requestTemplate.body.jsonataContent: " + ex.getMessage());
            }
        }
    }

    /**
     * Validates template and binding size limits, for request #0 and, in chain order, for every
     * entry of {@code additionalRequests}. Throws ValidationException (400) if any limit is exceeded.
     */
    public void validateTemplateLimits(TestSuiteRequestDto dto) {
        if (dto.getRequestTemplate() != null) {
            validateJsonFieldSize(dto.getRequestTemplate(), "requestTemplate");
        }
        if (dto.getArgumentTemplate() != null) {
            validateJsonFieldSize(dto.getArgumentTemplate(), "argumentTemplate");
        }
        if (dto.getToolRef() != null) {
            validateJsonFieldSize(dto.getToolRef(), "toolRef");
        }
        if (dto.getMcpDeploymentRef() != null) {
            validateJsonFieldSize(dto.getMcpDeploymentRef(), "mcpDeploymentRef");
        }
        validateBindingLimits(dto.getInputBindings(), "");

        List<RequestDefinitionDto> additionalRequests = dto.getAdditionalRequests();
        if (additionalRequests != null) {
            for (int i = 0; i < additionalRequests.size(); i++) {
                RequestDefinitionDto request = additionalRequests.get(i);
                if (request == null) {
                    continue;
                }
                String prefix = additionalRequestPrefix(i);
                if (request.getRequestTemplate() != null) {
                    validateJsonFieldSize(request.getRequestTemplate(), prefix + "requestTemplate");
                }
                validateBindingLimits(request.getInputBindings(), prefix);
            }
        }
    }

    private void validateBindingLimits(List<InputBindingDto> bindings, String prefix) {
        if (bindings == null) {
            return;
        }
        if (bindings.size() > validationProperties.getMaxBindingsCount()) {
            throw new ValidationException(prefix + "inputBindings count (" + bindings.size() + ") exceeds maximum of "
                    + validationProperties.getMaxBindingsCount());
        }
        Set<String> seen = new HashSet<>();
        for (InputBindingDto b : bindings) {
            if (b != null && b.getTemplateVariable() != null && !seen.add(b.getTemplateVariable())) {
                throw new ValidationException(
                        prefix + "Duplicate templateVariable '" + b.getTemplateVariable() + "' in inputBindings");
            }
        }
    }

    private static String additionalRequestPrefix(int index) {
        return "additionalRequests[" + index + "].";
    }

    private SuiteType resolveSuiteType(TestSuiteRequestDto dto) {
        return dto.getSuiteType() != null ? dto.getSuiteType() : SuiteType.DEPLOYMENT;
    }

    private void validateJsonFieldSize(Object value, String fieldName) {
        try {
            String serialized = objectMapper.writeValueAsString(value);
            if (serialized.length() > validationProperties.getMaxTemplateSizeBytes()) {
                throw new ValidationException(fieldName + " exceeds maximum size of "
                        + validationProperties.getMaxTemplateSizeBytes() + " bytes");
            }
        } catch (JacksonException e) {
            throw new ValidationException(fieldName + ": failed to serialize for size check");
        }
    }
}
