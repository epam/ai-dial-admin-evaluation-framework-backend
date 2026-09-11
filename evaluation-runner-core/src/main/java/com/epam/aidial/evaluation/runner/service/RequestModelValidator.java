package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyValidationException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Validates the request-body model used by fixed-path model-selecting APIs. */
@Component
@LogExecution
public class RequestModelValidator {

    /**
     * Validates plain authored JSON content without resolving template values.
     *
     * @return a diagnostic for invalid content, or empty when valid or outside the canonical paths
     */
    public Optional<String> validateContent(String path, Map<String, Object> content, String deploymentId) {
        return validate(path, content, deploymentId);
    }

    /**
     * Validates a resolved body and throws when a canonical fixed-path request is invalid.
     * Non-canonical paths are outside this rule and pass through unchanged.
     */
    public void validateForExecution(String path, ResolvedBodyDto body, String deploymentId) {
        if (!ModelSelectingEndpointPaths.ALL.contains(path)) {
            return;
        }
        if (!(body instanceof ResolvedJsonBodyDto jsonBody)) {
            throw new RequestBodyValidationException("Request body for endpoint '" + path
                    + "' must be a JSON object with a string 'model' matching deployment ID '" + deploymentId + "'");
        }
        validate(path, jsonBody.getContent(), deploymentId).ifPresent(message -> {
            throw new RequestBodyValidationException(message);
        });
    }

    private Optional<String> validate(String path, Map<String, Object> content, String deploymentId) {
        if (!ModelSelectingEndpointPaths.ALL.contains(path)) {
            return Optional.empty();
        }
        if (content == null) {
            return Optional.of("Request body for endpoint '" + path
                    + "' must be a JSON object with a string 'model' matching deployment ID '" + deploymentId + "'");
        }
        if (!content.containsKey("model")) {
            return Optional.of("Request body 'model' is required for endpoint '" + path
                    + "' and must match deployment ID '" + deploymentId + "'");
        }
        final Object modelValue = content.get("model");
        if (!(modelValue instanceof String model)) {
            return Optional.of(
                    "Request body 'model' must be a non-null string matching deployment ID '" + deploymentId + "'");
        }
        if (TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(model).find()) {
            return Optional.of("Request body 'model' must be a string constant without ${{...}} placeholders and match "
                    + "deployment ID '" + deploymentId + "'");
        }
        if (!model.equals(deploymentId)) {
            return Optional.of(
                    "Request body 'model' value '" + model + "' does not match deployment ID '" + deploymentId + "'");
        }
        return Optional.empty();
    }
}
