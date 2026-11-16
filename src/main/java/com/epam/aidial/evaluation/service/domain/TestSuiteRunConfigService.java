package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunConfigResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Exposes the run-related limits the backend currently enforces, so clients can bound run-form inputs. */
@Service
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRunConfigService {

    private final EvaluationRunProperties evaluationRunProperties;

    public TestSuiteRunConfigResponseDto getRunConfig() {
        return TestSuiteRunConfigResponseDto.builder()
                .maxConcurrencyLevel(evaluationRunProperties.getExecution().getMaxConcurrencyLevel())
                .build();
    }
}
