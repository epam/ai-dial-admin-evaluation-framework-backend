package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.ExecutionSettingsValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link RunConfigDto} against the configured limits. Shared by run creation and schedule
 * creation/editing so a scheduled run is rejected at write time under exactly the rules that would reject it when
 * it fires.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class RunConfigValidator {

    private final TestSuiteRunProperties properties;
    private final ExecutionSettingsValidator executionSettingsValidator;

    /**
     * @throws ValidationException when {@code numberOfRuns} exceeds the configured maximum or the execution/retry
     *     settings are invalid
     */
    public void validate(RunConfigDto config) {
        final int maxRuns = properties.getRunConfig().getMaxNumberOfRuns();
        if (config.getNumberOfRuns() > maxRuns) {
            throw new ValidationException("numberOfRuns must not exceed " + maxRuns);
        }
        executionSettingsValidator.validate(config.getExecution(), config.getRetry());
    }
}
