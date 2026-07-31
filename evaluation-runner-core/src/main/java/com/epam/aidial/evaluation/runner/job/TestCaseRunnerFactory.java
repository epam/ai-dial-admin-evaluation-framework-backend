package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates a fresh {@link TestCaseRunner} per run, so its semaphore/rate-limit bucket stay correctly
 * scoped to a single run's lifetime instead of being shared (or reset) across unrelated runs.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseRunnerFactory {

    private final EvaluationWorker evaluationWorker;
    private final TestCaseRunResultFactory testCaseRunResultFactory;
    private final Clock clock;

    public TestCaseRunner create(
            EvaluationContext context,
            List<ResponseColumnDefinitionDto> responseColumns,
            ResultBatchWriter resultsWriter) {
        return new TestCaseRunner(
                evaluationWorker, testCaseRunResultFactory, clock, context, responseColumns, resultsWriter);
    }
}
