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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRequestValidator {

    private final JsonataEvaluationService jsonataEvaluationService;
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
        List<ResponseColumnDefinitionDto> responseColumns = dto.getResponseColumns();
        if (responseColumns != null && !responseColumns.isEmpty()) {
            Set<String> seenNames = new HashSet<>();
            for (int i = 0; i < responseColumns.size(); i++) {
                ResponseColumnDefinitionDto col = responseColumns.get(i);
                if (col == null) {
                    continue;
                }
                if (col.getName() == null || col.getName().isBlank()) {
                    throw new ValidationException("responseColumns[" + i + "]: name must not be blank");
                }
                if (!seenNames.add(col.getName())) {
                    throw new ValidationException("responseColumns: duplicate column name '" + col.getName() + "'");
                }
                if (col.getExpression() == null || col.getExpression().isBlank()) {
                    throw new ValidationException("responseColumns[" + i + "]: expression must not be blank");
                }
                try {
                    jsonataEvaluationService.validateExpression(col.getExpression());
                } catch (ValidationException ex) {
                    throw new ValidationException(
                            "responseColumns[" + i + "] ('" + col.getName() + "').expression: " + ex.getMessage());
                }
            }
        }
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
        if (dto.getInputBindings() != null
                && dto.getInputBindings().size() > validationProperties.getMaxBindingsCount()) {
            throw new ValidationException(
                    "inputBindings count (" + dto.getInputBindings().size() + ") exceeds maximum of "
                            + validationProperties.getMaxBindingsCount());
        }
        if (dto.getInputBindings() != null) {
            Set<String> seen = new HashSet<>();
            for (InputBindingDto b : dto.getInputBindings()) {
                if (b != null && b.getTemplateVariable() != null && !seen.add(b.getTemplateVariable())) {
                    throw new ValidationException(
                            "Duplicate templateVariable '" + b.getTemplateVariable() + "' in inputBindings");
                }
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
        } catch (JsonProcessingException e) {
            throw new ValidationException(fieldName + ": failed to serialize for size check");
        }
    }
}
