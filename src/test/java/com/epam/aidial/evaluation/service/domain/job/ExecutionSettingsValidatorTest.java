package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.service.domain.dto.ExecutionSettingsDto;
import com.epam.aidial.evaluation.service.domain.dto.RetryPolicyDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExecutionSettingsValidator")
class ExecutionSettingsValidatorTest {

    private ExecutionSettingsValidator validator;

    @BeforeEach
    void setUp() {
        EvaluationRunProperties props = new EvaluationRunProperties();

        EvaluationRunProperties.Execution exec = new EvaluationRunProperties.Execution();
        exec.setDefaultConcurrencyLevel(5);
        exec.setMaxConcurrencyLevel(50);
        exec.setDefaultRequestTimeoutMs(30000L);
        exec.setMaxRequestTimeoutMs(600000L);
        exec.setDefaultRateLimitRps(null);
        exec.setResultBatchSize(100);
        exec.setMaxResponseSizeBytes(10485760L);
        exec.setCancellationGracePeriodMs(5000L);
        exec.setHeaderBlacklist(List.of("Authorization"));
        props.setExecution(exec);

        EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
        retry.setDefaultMaxRetries(0);
        retry.setMaxMaxRetries(10);
        retry.setDefaultRetryDelayMs(1000L);
        retry.setMaxRetryDelayMs(60000L);
        retry.setDefaultRetryBackoffMultiplier(2.0);
        retry.setMaxRetryBackoffMultiplier(10.0);
        props.setRetry(retry);

        validator = new ExecutionSettingsValidator(props);
    }

    @Test
    @DisplayName("Should not throw when both execution and retry are null")
    void validate_nullExecution_nullRetry_noException() {
        assertThatCode(() -> validator.validate(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should not throw when concurrency is within the allowed maximum")
    void validate_validConcurrency_noException() {
        ExecutionSettingsDto execution =
                ExecutionSettingsDto.builder().concurrencyLevel(50).build();

        assertThatCode(() -> validator.validate(execution, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw when concurrency exceeds the allowed maximum")
    void validate_concurrencyExceedsMax_throwsValidationException() {
        ExecutionSettingsDto execution =
                ExecutionSettingsDto.builder().concurrencyLevel(51).build();

        assertThatThrownBy(() -> validator.validate(execution, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("concurrencyLevel must not exceed 50");
    }

    @Test
    @DisplayName("Should not throw when requestTimeoutMs is within the allowed maximum")
    void validate_validRequestTimeout_noException() {
        ExecutionSettingsDto execution =
                ExecutionSettingsDto.builder().requestTimeoutMs(600000L).build();

        assertThatCode(() -> validator.validate(execution, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw when requestTimeoutMs exceeds the allowed maximum")
    void validate_requestTimeoutExceedsMax_throwsValidationException() {
        ExecutionSettingsDto execution =
                ExecutionSettingsDto.builder().requestTimeoutMs(600001L).build();

        assertThatThrownBy(() -> validator.validate(execution, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTimeoutMs must not exceed 600000");
    }

    @Test
    @DisplayName("Should not throw when all retry settings are within allowed maximums")
    void validate_validRetrySettings_noException() {
        RetryPolicyDto retry = RetryPolicyDto.builder()
                .maxRetries(10)
                .retryDelayMs(60000L)
                .retryBackoffMultiplier(10.0)
                .build();

        assertThatCode(() -> validator.validate(null, retry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw when maxRetries exceeds the allowed maximum")
    void validate_maxRetriesExceedsMax_throwsValidationException() {
        RetryPolicyDto retry = RetryPolicyDto.builder().maxRetries(11).build();

        assertThatThrownBy(() -> validator.validate(null, retry))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxRetries must not exceed 10");
    }

    @Test
    @DisplayName("Should throw when retryDelayMs exceeds the allowed maximum")
    void validate_retryDelayExceedsMax_throwsValidationException() {
        RetryPolicyDto retry = RetryPolicyDto.builder().retryDelayMs(60001L).build();

        assertThatThrownBy(() -> validator.validate(null, retry))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("retryDelayMs must not exceed 60000");
    }

    @Test
    @DisplayName("Should throw when retryBackoffMultiplier exceeds the allowed maximum")
    void validate_backoffMultiplierExceedsMax_throwsValidationException() {
        RetryPolicyDto retry =
                RetryPolicyDto.builder().retryBackoffMultiplier(10.1).build();

        assertThatThrownBy(() -> validator.validate(null, retry))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("retryBackoffMultiplier must not exceed 10.0");
    }

    @Test
    @DisplayName("Should report all violations in a single message when multiple limits are exceeded")
    void validate_multipleViolations_allReportedInMessage() {
        ExecutionSettingsDto execution = ExecutionSettingsDto.builder()
                .concurrencyLevel(100)
                .requestTimeoutMs(999999L)
                .build();

        RetryPolicyDto retry = RetryPolicyDto.builder()
                .maxRetries(20)
                .retryDelayMs(100000L)
                .retryBackoffMultiplier(15.0)
                .build();

        assertThatThrownBy(() -> validator.validate(execution, retry))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("concurrencyLevel must not exceed 50")
                .hasMessageContaining("requestTimeoutMs must not exceed 600000")
                .hasMessageContaining("maxRetries must not exceed 10")
                .hasMessageContaining("retryDelayMs must not exceed 60000")
                .hasMessageContaining("retryBackoffMultiplier must not exceed 10.0");
    }
}
