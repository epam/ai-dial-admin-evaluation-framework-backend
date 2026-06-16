package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseRunInputsRetentionJob {

    private final TestCaseRunInputRepository testCaseRunInputRepository;
    private final EvaluationRunProperties evaluationRunProperties;

    @Scheduled(fixedDelay = 86_400_000)
    public void deleteExpiredInputs() {
        log.info("Starting test case run inputs retention cleanup");
        try {
            int deleted = testCaseRunInputRepository.deleteByRunIdsInTerminalStateOlderThan(
                    evaluationRunProperties.getInputsRetentionDuration());
            log.info("Deleted {} expired test_case_run_inputs rows", deleted);
        } catch (Exception e) {
            log.warn("Test case run inputs retention cleanup failed: {}", e.getMessage(), e);
        }
    }
}
