package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunConfigResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestSuiteRunConfigServiceTest {

    @Test
    @DisplayName("Should return configured max concurrency level")
    void shouldReturnConfiguredMaxConcurrencyLevel() {
        final EvaluationRunProperties.Execution execution = new EvaluationRunProperties.Execution();
        execution.setMaxConcurrencyLevel(7);
        final EvaluationRunProperties properties = new EvaluationRunProperties();
        properties.setExecution(execution);

        final TestSuiteRunConfigResponseDto config = new TestSuiteRunConfigService(properties).getRunConfig();

        assertThat(config.getMaxConcurrencyLevel()).isEqualTo(7);
    }
}
