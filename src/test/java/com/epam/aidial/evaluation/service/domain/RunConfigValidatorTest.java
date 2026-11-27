package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.runner.dto.ExecutionSettingsDto;
import com.epam.aidial.evaluation.runner.dto.RetryPolicyDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.ExecutionSettingsValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("RunConfigValidator")
@ExtendWith(MockitoExtension.class)
class RunConfigValidatorTest {

    @Mock
    private ExecutionSettingsValidator executionSettingsValidator;

    private RunConfigValidator validator;

    @BeforeEach
    void setUp() {
        final TestSuiteRunProperties.RunConfig runConfig = new TestSuiteRunProperties.RunConfig();
        runConfig.setMaxNumberOfRuns(10);
        final TestSuiteRunProperties properties = new TestSuiteRunProperties();
        properties.setRunConfig(runConfig);
        validator = new RunConfigValidator(properties, executionSettingsValidator);
    }

    @Test
    @DisplayName("shouldRejectNumberOfRunsAboveConfiguredMaximumWithoutCheckingExecutionSettings")
    void shouldRejectNumberOfRunsAboveConfiguredMaximum() {
        final RunConfigDto config = RunConfigDto.builder().numberOfRuns(11).build();

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessage("numberOfRuns must not exceed 10");
        verifyNoInteractions(executionSettingsValidator);
    }

    @Test
    @DisplayName("shouldAcceptNumberOfRunsEqualToMaximumAndDelegateExecutionAndRetryToExecutionSettingsValidator")
    void shouldAcceptMaximumAndDelegateExecutionSettings() {
        final ExecutionSettingsDto execution = new ExecutionSettingsDto();
        final RetryPolicyDto retry = new RetryPolicyDto();
        final RunConfigDto config = RunConfigDto.builder()
                .numberOfRuns(10)
                .execution(execution)
                .retry(retry)
                .build();

        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();

        verify(executionSettingsValidator).validate(execution, retry);
    }
}
