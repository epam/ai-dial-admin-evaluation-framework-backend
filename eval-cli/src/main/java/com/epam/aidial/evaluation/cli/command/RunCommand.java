package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.cli.service.FetchService;
import com.epam.aidial.evaluation.cli.service.RunOrchestrationService;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import java.io.File;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Runs all test cases for each selected suite against a CLI-specified target deployment, writing
 * results to CSV under {@code cli.workDir}.
 *
 * <p>Loads previously fetched suite bundles from disk (from the {@code fetch} step) and delegates
 * execution entirely to {@link RunOrchestrationService} / {@code evaluation-runner-core}'s
 * {@code TestCaseRunner}. Target DIAL Core host/authentication is entirely env-var-configured
 * ({@code DIAL_CORE_URL}, {@code DIAL_CORE_API_KEY}) — there is no per-invocation override option.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@Command(
        name = "run",
        description = "Execute test cases for each selected suite against the specified target deployment,"
                + " writing results to CSV under cli.workDir.")
public class RunCommand implements Runnable {

    @Mixin
    private SuitesOption suitesOption;

    @Mixin
    private DeploymentIdOption deploymentIdOption;

    private final FetchService fetchService;
    private final RunOrchestrationService runOrchestrationService;
    private final EvalCliProperties cliProperties;

    @Override
    public void run() {
        final List<UUID> suites = suitesOption.resolve();
        final DeploymentReferenceDto targetRef = deploymentIdOption.resolve();

        log.info("Running {} selected suite(s)", suites.size());

        for (UUID sourceSuiteId : suites) {
            final SuiteFetchBundle bundle = fetchService.load(sourceSuiteId);
            final File csvFile = runOrchestrationService.run(bundle, targetRef, cliProperties.getWorkDir());
            log.info("Results written to {}", csvFile.getAbsolutePath());
        }
        log.info("Run step complete");
    }
}
