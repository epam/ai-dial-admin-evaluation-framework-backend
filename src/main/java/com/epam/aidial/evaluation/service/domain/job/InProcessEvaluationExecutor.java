package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.ResultBatchWriter;
import com.epam.aidial.evaluation.runner.job.TestCaseRunner;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-process evaluation executor. Pages from test_case_run_inputs when available (snapshot runs), or
 * falls back to live test cases for legacy runs. Delegates concurrent dispatch to {@link TestCaseRunner}
 * per page, collects results into {@link ResultBatchWriter}, and handles completion/failure/cancellation.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class InProcessEvaluationExecutor implements EvaluationExecutor {

    private static final int PAGE_SIZE = 100;

    private final TestCaseRepository testCaseRepository;
    private final TestCaseRunInputRepository testCaseRunInputRepository;
    private final TestCaseRunnerFactory testCaseRunnerFactory;
    private final PostgresResultBatchWriterFactory resultBatchWriterFactory;

    @Override
    public void execute(EvaluationContext context) {
        List<ResponseColumnDefinitionDto> responseColumns = context.getSnapshotResponseColumns();

        log.info(
                "Starting deployment evaluation for run {}: {} test case(s), {} run(s) each, concurrency={}",
                context.getRunId(),
                context.getNumberOfTestCases(),
                context.getNumberOfRuns(),
                context.getConcurrencyLevel());

        ResultBatchWriter writer = resultBatchWriterFactory.createWriter(
                context.getResultBatchSize(),
                context.getRunId(),
                context.getSuiteId(),
                context.getNumberOfTestCases() * context.getNumberOfRuns());
        TestCaseRunner testCaseRunner = testCaseRunnerFactory.create(context, responseColumns, writer);

        boolean useInputsTable = testCaseRunInputRepository.existsByRunId(context.getRunId());

        try {
            int offset = 0;
            List<TestCaseRunInput> page;
            do {
                if (context.getCancellationSignal().get()) {
                    break;
                }

                page = fetchPage(context, useInputsTable, offset);
                testCaseRunner.submit(page);

                offset += PAGE_SIZE;
            } while (page.size() == PAGE_SIZE
                    && !context.getCancellationSignal().get());

            testCaseRunner.awaitCompletion();
        } catch (Exception e) {
            log.warn("Executor error for run {}: {}", context.getRunId(), e.getMessage(), e);
            try {
                writer.flush();
            } catch (Exception flushEx) {
                log.error("Best-effort flush failed for run {}: {}", context.getRunId(), flushEx.getMessage(), flushEx);
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        } finally {
            try {
                writer.flush();
            } catch (Exception e) {
                log.error("Final flush failed for run {}: {}", context.getRunId(), e.getMessage(), e);
            }
        }
    }

    private List<TestCaseRunInput> fetchPage(EvaluationContext context, boolean useInputsTable, int offset) {
        if (useInputsTable) {
            return testCaseRunInputRepository.findByRunId(context.getRunId(), offset, PAGE_SIZE);
        }
        // Legacy fallback: page from live dataset test cases, wrap as TestCaseRunInput.
        // datasetId is sourced from the snapshot's datasetRef (always populated by resolveSnapshot
        // under the version-2 snapshot model). Disabled-ids exclusion is intentionally empty here
        // because the live suite's disabledTestCaseIds was not captured at run start for legacy runs;
        // override fields are left null since per-test-case overrides no longer exist on the model.
        List<TestCase> cases = testCaseRepository.findValidByDatasetIdExcludingIds(
                context.getDatasetId(), List.of(), offset, PAGE_SIZE);
        List<TestCaseRunInput> inputs = new ArrayList<>(cases.size());
        for (TestCase tc : cases) {
            inputs.add(TestCaseRunInput.builder()
                    .runId(context.getRunId())
                    .position(offset + inputs.size())
                    .testCaseId(tc.getId())
                    .testCaseName(tc.getTestCaseName())
                    .testCaseData(tc.getData())
                    .multiTurnData(tc.getMultiTurnData())
                    .build());
        }
        return inputs;
    }
}
