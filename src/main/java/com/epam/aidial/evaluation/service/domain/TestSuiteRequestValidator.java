package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.constants.JsonataReservedNames;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
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
    private void validateRequestTemplateBody(RequestTemplateDto requestTemplate) {
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
            throw new ValidationException("requestTemplate.body: content and jsonataContent are mutually exclusive");
        }
        if (jsonataContent != null) {
            String neutralized = jsonataSourcePreprocessor.neutralize(jsonataContent);
            try {
                jsonataEvaluationService.validateExpression(neutralized);
            } catch (ValidationException ex) {
                throw new ValidationException("requestTemplate.body.jsonataContent: " + ex.getMessage());
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
        } catch (JacksonException e) {
            throw new ValidationException(fieldName + ": failed to serialize for size check");
        }
    }
}
