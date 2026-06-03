package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.service.domain.dto.ExecutionSettingsDto;
import com.epam.aidial.evaluation.service.domain.dto.RetryPolicyDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates per-run execution and retry settings against system maximums.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ExecutionSettingsValidator {

    private final EvaluationRunProperties properties;

    public void validate(ExecutionSettingsDto execution, RetryPolicyDto retry) {
        List<String> errors = new ArrayList<>();

        if (execution != null) {
            validateExecution(execution, errors);
        }
        if (retry != null) {
            validateRetry(retry, errors);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join("; ", errors));
        }
    }

    private void validateExecution(ExecutionSettingsDto execution, List<String> errors) {
        EvaluationRunProperties.Execution config = properties.getExecution();

        if (execution.getConcurrencyLevel() != null
                && execution.getConcurrencyLevel() > config.getMaxConcurrencyLevel()) {
            errors.add("concurrencyLevel must not exceed " + config.getMaxConcurrencyLevel());
        }
        if (execution.getRequestTimeoutMs() != null
                && execution.getRequestTimeoutMs() > config.getMaxRequestTimeoutMs()) {
            errors.add("requestTimeoutMs must not exceed " + config.getMaxRequestTimeoutMs());
        }
    }

    private void validateRetry(RetryPolicyDto retry, List<String> errors) {
        EvaluationRunProperties.Retry config = properties.getRetry();

        if (retry.getMaxRetries() != null && retry.getMaxRetries() > config.getMaxMaxRetries()) {
            errors.add("maxRetries must not exceed " + config.getMaxMaxRetries());
        }
        if (retry.getRetryDelayMs() != null && retry.getRetryDelayMs() > config.getMaxRetryDelayMs()) {
            errors.add("retryDelayMs must not exceed " + config.getMaxRetryDelayMs());
        }
        if (retry.getRetryBackoffMultiplier() != null
                && retry.getRetryBackoffMultiplier() > config.getMaxRetryBackoffMultiplier()) {
            errors.add("retryBackoffMultiplier must not exceed " + config.getMaxRetryBackoffMultiplier());
        }
    }
}
