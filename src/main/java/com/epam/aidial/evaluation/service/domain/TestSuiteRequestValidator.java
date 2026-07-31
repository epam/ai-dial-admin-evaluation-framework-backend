package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.constants.JsonataReservedNames;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
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
        validateRequestTemplateBody(dto.getRequestTemplate());

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
                if (JsonataReservedNames.RESERVED_COLUMN_NAMES.contains(col.getName())) {
                    throw new ValidationException("responseColumns[" + i + "] ('" + col.getName()
                            + "'): name is reserved (JSONata built-in function or frame variable)");
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
     * Validates an {@code application/json} request body whose {@code content} is a JSONata
     * source {@code String} (parses as valid JSONata) or a legacy structural {@code Map}
     * (unchanged, no JSONata validation needed since a Map is validated at resolution time). Any
     * other content type (not null, not Map, not String) is rejected.
     */
    private void validateRequestTemplateBody(RequestTemplateDto requestTemplate) {
        if (requestTemplate == null) {
            return;
        }
        RequestBodyDto body = requestTemplate.getBody();
        if (!(body instanceof JsonRequestBodyDto jsonBody)) {
            return;
        }
        Object content = jsonBody.getContent();
        if (content == null || content instanceof Map) {
            return;
        }
        if (content instanceof String jsonataSource) {
            try {
                jsonataEvaluationService.validateExpression(jsonataSource);
            } catch (ValidationException ex) {
                throw new ValidationException("requestTemplate.body.content: " + ex.getMessage());
            }
            return;
        }
        throw new ValidationException("requestTemplate.body.content: must be a JSON object or a JSONata source string");
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
        } catch (JacksonException e) {
            throw new ValidationException(fieldName + ": failed to serialize for size check");
        }
    }
}
