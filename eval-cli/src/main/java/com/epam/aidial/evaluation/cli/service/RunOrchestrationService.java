package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.csv.CsvResultBatchWriter;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.TestCaseRunner;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * @param targetDeploymentRef the deployment reference to override the suite's source-side ref;
     *                             {@code null} to fall back to the fetched suite's own recorded
     *                             {@code deploymentRef}
     * @param workDir             the working directory where the results CSV is written
     * @return the {@link File} pointing to the produced results CSV
     * @throws IllegalStateException if the fetched suite's {@code endpointRef}/{@code requestTemplate}
     *                                fails {@link SuiteContractValidator}; if {@code
     *                                targetDeploymentRef} is {@code null} and the suite has no recorded
     *                                {@code deploymentRef} either; if the bundle's dataset
     *                                schema is absent and some fetched test case carries per-turn
     *                                data (stale bundle — re-run {@code fetch}); or if the suite is
     *                                an MCP tool suite and some fetched test case carries per-turn
     *                                data (unsupported combination, mirroring the EF backend's guard)
     */
    public File run(SuiteFetchBundle bundle, DeploymentReferenceDto targetDeploymentRef, String workDir) {
        suiteContractValidator.validate(bundle.getSuite());
        final DeploymentReferenceDto resolvedDeploymentRef =
                resolveTargetDeploymentRef(bundle.getSuite(), targetDeploymentRef);

        final List<TestCaseResponseDto> testCases = bundle.getTestCases();
        final boolean hasMultiTurnCase = testCases.stream().anyMatch(this::isMultiTurn);

        requireFreshSchemaForMultiTurn(bundle, hasMultiTurnCase);
        rejectMcpSuiteWithMultiTurnCases(bundle.getSuite(), hasMultiTurnCase);

        log.info(
                "Starting run for suite '{}' ({}) — {} test case(s) against deployment '{}'",
                bundle.getSuite().getName(),
                bundle.getSourceSuiteId(),
                testCases.size(),
                resolvedDeploymentRef.getId());

        final EvaluationContext context = evaluationContextFactory.create(
                bundle.getSuite(), testCases.size(), resolvedDeploymentRef, bundle.getTestCaseSchema());

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

    /**
     * Fails fast when the bundle's dataset schema is absent (fetched by an earlier CLI version)
     * and at least one fetched test case carries per-turn data — running such a case without a
     * schema would silently execute it single-turn instead of failing loudly (design.md Decision 7).
     * A stale bundle with no multi-turn case is genuinely equivalent to a fresh one and is not
     * rejected.
     */
    private void requireFreshSchemaForMultiTurn(SuiteFetchBundle bundle, boolean hasMultiTurnCase) {
        if (bundle.getTestCaseSchema() == null && hasMultiTurnCase) {
            throw new IllegalStateException(
                    "Fetch bundle for suite " + bundle.getSourceSuiteId() + " has no dataset test-case schema, but"
                            + " some fetched test case carries per-turn data. Re-run 'fetch' for this suite before"
                            + " running it — an older bundle cannot be executed as multi-turn.");
        }
    }

    /**
     * Rejects an MCP tool suite that has any fetched test case carrying per-turn data, before any
     * target invocation — mirroring the EF backend's own run-creation guard (design.md Decision 8).
     */
    private void rejectMcpSuiteWithMultiTurnCases(TestSuiteResponseDto suite, boolean hasMultiTurnCase) {
        if (suite.getSuiteType() == SuiteType.MCP_TOOL && hasMultiTurnCase) {
            throw new IllegalStateException("Suite " + suite.getId() + " ('" + suite.getName()
                    + "') is an MCP tool suite with at least one multi-turn test case — MCP suites do not support"
                    + " multi-turn test cases.");
        }
    }

    private boolean isMultiTurn(TestCaseResponseDto testCase) {
        return testCase.getMultiTurnData() != null
                && !testCase.getMultiTurnData().isEmpty();
    }

    /**
     * Resolves the deployment reference to run against: the CLI-provided {@code cliTargetDeploymentRef}
     * takes precedence when present; otherwise falls back to the suite's own recorded {@code
     * deploymentRef} from the source EF.
     *
     * @throws IllegalStateException if neither is available
     */
    private DeploymentReferenceDto resolveTargetDeploymentRef(
            TestSuiteResponseDto suite, DeploymentReferenceDto cliTargetDeploymentRef) {
        if (cliTargetDeploymentRef != null) {
            return cliTargetDeploymentRef;
        }
        final DeploymentReferenceDto suiteDeploymentRef = suite.getDeploymentRef();
        if (suiteDeploymentRef == null || suiteDeploymentRef.getId() == null) {
            throw new IllegalStateException("Suite " + suite.getId() + " ('" + suite.getName()
                    + "') has no recorded deploymentRef and --deployment-id was not provided");
        }
        return suiteDeploymentRef;
    }

    private List<TestCaseRunInput> mapInputs(List<TestCaseResponseDto> testCases, UUID runId) {
        int position = 0;
        final List<TestCaseRunInput> inputs = new ArrayList<>(testCases.size());
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
