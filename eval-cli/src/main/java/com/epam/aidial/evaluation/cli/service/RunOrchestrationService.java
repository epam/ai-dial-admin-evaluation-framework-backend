package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.client.source.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.cli.csv.CsvResultBatchWriter;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.TestCaseRunner;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a single suite's Phase 1 execution run against the CLI-configured target deployment.
 *
 * <p>Delegates all concurrency, rate limiting, retry, and per-worker token propagation to
 * {@link TestCaseRunnerFactory}/{@link TestCaseRunner} — these are NOT reimplemented here.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class RunOrchestrationService {

    private final TestCaseRunnerFactory testCaseRunnerFactory;
    private final EvaluationContextFactory evaluationContextFactory;
    private final TestCaseRunInputMapper testCaseRunInputMapper;
    private final SuiteContractValidator suiteContractValidator;

    /**
     * Runs all test cases in the bundle against the configured target deployment, writing results to
     * a CSV file at {@code <workDir>/<sourceSuiteId>-results.csv}.
     *
     * @param bundle              the suite fetch bundle containing suite config and test cases
     * @param targetDeploymentRef the deployment reference to override the suite's source-side ref
     * @param workDir             the working directory where the results CSV is written
     * @return the {@link File} pointing to the produced results CSV
     * @throws IllegalStateException if the fetched suite's {@code endpointRef}/{@code requestTemplate}
     *                                fails {@link SuiteContractValidator}
     */
    public File run(SuiteFetchBundle bundle, DeploymentReferenceDto targetDeploymentRef, String workDir) {
        suiteContractValidator.validate(bundle.getSuite());

        final List<TestCaseResponseDto> testCases = bundle.getTestCases();
        log.info(
                "Starting run for suite '{}' ({}) — {} test case(s) against deployment '{}'",
                bundle.getSuite().getName(),
                bundle.getSourceSuiteId(),
                testCases.size(),
                targetDeploymentRef.getId());

        final EvaluationContext context =
                evaluationContextFactory.create(bundle.getSuite(), testCases.size(), targetDeploymentRef);

        final List<TestCaseRunInput> inputs = mapInputs(testCases, context.getRunId());
        final Path csvPath = csvPath(workDir, bundle.getSourceSuiteId());

        try {
            Files.createDirectories(csvPath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create work directory for CSV output: " + e.getMessage(), e);
        }

        try (Writer writer = Files.newBufferedWriter(csvPath);
                CsvResultBatchWriter csvWriter = new CsvResultBatchWriter(writer)) {

            final TestCaseRunner runner =
                    testCaseRunnerFactory.create(context, context.getSnapshotResponseColumns(), csvWriter);
            runner.submit(inputs);
            runner.awaitCompletion();
            csvWriter.flush();

        } catch (IOException e) {
            throw new RuntimeException("Failed to write results CSV: " + e.getMessage(), e);
        }

        log.info("Run complete for suite {} — results at {}", bundle.getSourceSuiteId(), csvPath.toAbsolutePath());
        return csvPath.toFile();
    }

    private List<TestCaseRunInput> mapInputs(List<TestCaseResponseDto> testCases, UUID runId) {
        int position = 0;
        final java.util.List<TestCaseRunInput> inputs = new java.util.ArrayList<>(testCases.size());
        for (TestCaseResponseDto tc : testCases) {
            final TestCaseRunInput input = testCaseRunInputMapper.toInput(tc);
            input.setRunId(runId);
            input.setPosition(position++);
            inputs.add(input);
        }
        return inputs;
    }

    private Path csvPath(String workDir, UUID sourceSuiteId) {
        return Path.of(workDir, sourceSuiteId + "-results.csv");
    }
}
