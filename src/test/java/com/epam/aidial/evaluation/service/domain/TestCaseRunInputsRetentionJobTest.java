package com.epam.aidial.evaluation.service.domain;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TestCaseRunInputsRetentionJob")
@ExtendWith(MockitoExtension.class)
class TestCaseRunInputsRetentionJobTest {

    @Mock
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @InjectMocks
    private TestCaseRunInputsRetentionJob job;

    @Test
    @DisplayName("calls repository delete with configured retention duration")
    void callsRepositoryDeleteWithConfiguredDuration() {
        Duration retention = Duration.ofDays(7);
        when(evaluationRunProperties.getInputsRetentionDuration()).thenReturn(retention);
        when(testCaseRunInputRepository.deleteByRunIdsInTerminalStateOlderThan(retention))
                .thenReturn(5);

        job.deleteExpiredInputs();

        verify(testCaseRunInputRepository).deleteByRunIdsInTerminalStateOlderThan(retention);
    }

    @Test
    @DisplayName("does not propagate exception when repository delete fails")
    void doesNotPropagateExceptionOnRepositoryFailure() {
        Duration retention = Duration.ofDays(1);
        when(evaluationRunProperties.getInputsRetentionDuration()).thenReturn(retention);
        when(testCaseRunInputRepository.deleteByRunIdsInTerminalStateOlderThan(retention))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should not throw — retention failures are logged and swallowed
        job.deleteExpiredInputs();
    }
}
