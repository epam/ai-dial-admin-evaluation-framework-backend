package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
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
    private final ChainNormalizer chainNormalizer;
    private final SchemaValidationService schemaValidationService;
    private final ObjectMapper objectMapper;
    private final ValidationProperties validationProperties;

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
        }
    }

    /**
     * Validates embedded schemas on TestSuite create/update.
     * Throws ValidationException (400) if any schema is malformed.
     */
    public void validateTestSuiteSchemas(TestSuiteRequestDto dto) {
        EndpointContractDto endpoint = dto.getEndpointRef();
        if (endpoint != null) {
            Optional<String> err = schemaValidationService.getSchemaValidationError(endpoint.getRequestBodySchema());
            if (err.isPresent()) {
                throw new ValidationException("endpointRef.requestBodySchema: " + err.get());
            }
            err = schemaValidationService.getSchemaValidationError(endpoint.getResponseBodySchema());
            if (err.isPresent()) {
                throw new ValidationException("endpointRef.responseBodySchema: " + err.get());
            }
            List<ParameterDefinitionDto> params = endpoint.getParameters();
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    ParameterDefinitionDto p = params.get(i);
                    if (p != null) {
                        Map<String, Object> paramSchema = p.getSchema();
                        err = schemaValidationService.getSchemaValidationError(paramSchema);
                        if (err.isPresent()) {
                            throw new ValidationException("endpointRef.parameters[" + i + "].schema: " + err.get());
                        }
                    }
                }
            }
        }
        // Every request in the chain gets the same response-column checks, request 0 included. Running them
        // only against the flat responseColumns would let a chain element save a blank name or a malformed
        // JSONata expression with 201, degrading at run time to a null column plus a per-row extraction
        // warning instead of the 400 the identical mistake earns on request 0.
        for (RequestSpec request : chainNormalizer.normalize(dto)) {
            validateElementEndpointSchemas(request);
            validateResponseColumns(request.safeResponseColumns(), requestPathPrefix(request));
        }
    }

    /**
     * A chain element carries its own {@code endpointRef}, so its body/response schemas need the same
     * well-formedness check as request 0's — nothing else validates them.
     */
    private void validateElementEndpointSchemas(RequestSpec request) {
        if (request.index() == 0 || request.endpointRef() == null) {
            // Request 0's endpointRef is validated above with its established message paths.
            return;
        }
        final String prefix = requestPathPrefix(request) + "endpointRef";
        Optional<String> err = schemaValidationService.getSchemaValidationError(
                request.endpointRef().getRequestBodySchema());
        if (err.isPresent()) {
            throw new ValidationException(prefix + ".requestBodySchema: " + err.get());
        }
        err = schemaValidationService.getSchemaValidationError(
                request.endpointRef().getResponseBodySchema());
        if (err.isPresent()) {
            throw new ValidationException(prefix + ".responseBodySchema: " + err.get());
        }
        final List<ParameterDefinitionDto> params = request.endpointRef().getParameters();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                final ParameterDefinitionDto p = params.get(i);
                if (p != null) {
                    final Optional<String> paramErr = schemaValidationService.getSchemaValidationError(p.getSchema());
                    if (paramErr.isPresent()) {
                        throw new ValidationException(prefix + ".parameters[" + i + "].schema: " + paramErr.get());
                    }
                }
            }
        }
    }

    private void validateResponseColumns(List<ResponseColumnDefinitionDto> responseColumns, String pathPrefix) {
        if (responseColumns == null || responseColumns.isEmpty()) {
            return;
        }
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < responseColumns.size(); i++) {
            ResponseColumnDefinitionDto col = responseColumns.get(i);
            if (col == null) {
                continue;
            }
            if (col.getName() == null || col.getName().isBlank()) {
                throw new ValidationException(pathPrefix + "responseColumns[" + i + "]: name must not be blank");
            }
            if (!seenNames.add(col.getName())) {
                throw new ValidationException(
                        pathPrefix + "responseColumns: duplicate column name '" + col.getName() + "'");
            }
            if (col.getExpression() == null || col.getExpression().isBlank()) {
                throw new ValidationException(pathPrefix + "responseColumns[" + i + "]: expression must not be blank");
            }
            try {
                jsonataEvaluationService.validateExpression(col.getExpression());
            } catch (ValidationException ex) {
                throw new ValidationException(pathPrefix + "responseColumns[" + i + "] ('" + col.getName()
                        + "').expression: " + ex.getMessage());
            }
        }
    }

    /**
     * Message path for a normalized chain request: empty for request 0 (its errors keep their established,
     * chain-unaware paths) and {@code additionalRequests[n].} for later elements, where {@code n} is the
     * element's index in the persisted array — one less than its chain index.
     */
    private static String requestPathPrefix(RequestSpec request) {
        return request.index() == 0 ? "" : "additionalRequests[" + (request.index() - 1) + "].";
    }

    /**
     * Validates template and binding size limits.
     * Throws ValidationException (400) if any limit is exceeded.
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
        // Per-request, over the normalized chain: a chain element's bindings are resolved by the same
        // Collectors.toMap(..., (a, b) -> a) that silently keeps the first of two bindings for one template
        // variable, so an unchecked duplicate loses a binding with no warning anywhere.
        for (RequestSpec request : chainNormalizer.normalize(dto)) {
            final String prefix = requestPathPrefix(request);
            if (request.index() > 0 && request.requestTemplate() != null) {
                validateJsonFieldSize(request.requestTemplate(), prefix + "requestTemplate");
            }
            validateBindings(request.inputBindings(), prefix);
        }
    }

    private void validateBindings(List<InputBindingDto> bindings, String pathPrefix) {
        if (bindings == null) {
            return;
        }
        if (bindings.size() > validationProperties.getMaxBindingsCount()) {
            throw new ValidationException(pathPrefix + "inputBindings count (" + bindings.size()
                    + ") exceeds maximum of " + validationProperties.getMaxBindingsCount());
        }
        Set<String> seen = new HashSet<>();
        for (InputBindingDto b : bindings) {
            if (b != null && b.getTemplateVariable() != null && !seen.add(b.getTemplateVariable())) {
                throw new ValidationException("Duplicate templateVariable '" + b.getTemplateVariable() + "' in "
                        + pathPrefix + "inputBindings");
            }
        }
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
