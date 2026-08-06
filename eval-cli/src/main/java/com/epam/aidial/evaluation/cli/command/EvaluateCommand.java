package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.cli.service.CloneService;
import com.epam.aidial.evaluation.cli.service.FetchService;
import com.epam.aidial.evaluation.cli.service.ImportService;
import com.epam.aidial.evaluation.cli.service.RunOrchestrationService;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import java.io.File;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Runs the full evaluation for each selected suite in sequence:
 * <ol>
 *   <li>Clone — ensure {@code <name>_<suffix>} exists on the source EF (reuse if present).</li>
 *   <li>Fetch — retrieve suite config and all test cases from the source EF.</li>
 *   <li>Run — execute test cases against the target deployment, writing results to CSV.</li>
 *   <li>Import — POST the CSV into the cloned suite; metric computation is triggered automatically.</li>
 * </ol>
 *
 * <p>The {@code cloneId} from the clone step is carried explicitly through this flow as the import
 * target — this is true regardless of whether the clone was newly created or reused. Target DIAL Core
 * host/authentication is entirely env-var-configured ({@code DIAL_CORE_URL}, {@code DIAL_CORE_API_KEY})
 * — there is no per-invocation override option.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@Command(
        name = "evaluate",
        description = "Run a full evaluation (clone → fetch → run → import) for each selected suite."
                + " Repeatable: re-running re-uses the existing clone and imports a new run.")
public class EvaluateCommand implements Runnable {

    @Mixin
    private SuitesOption suitesOption;

    @Mixin
    private CloneSuffixOption cloneSuffixOption;

    @Option(
            names = {"--deployment-id"},
            required = true,
            description = "Target deployment ID to send requests to (overrides the suite's recorded ref).")
    private String deploymentId;

    private final CloneService cloneService;
    private final FetchService fetchService;
    private final RunOrchestrationService runOrchestrationService;
    private final ImportService importService;
    private final EvalCliProperties cliProperties;

    @Override
    public void run() {
        final List<UUID> suites = suitesOption.resolve();
        final String cloneSuffix = cloneSuffixOption.resolve();
        final DeploymentReferenceDto targetRef = DeploymentReferenceDto.builder()
                .id(deploymentId)
                .name(deploymentId)
                .build();

        log.info("Starting evaluation for {} selected suite(s) against deployment '{}'", suites.size(), deploymentId);

        for (UUID sourceSuiteId : suites) {
            evaluate(sourceSuiteId, targetRef, cloneSuffix);
        }

        log.info("Evaluation complete for all {} suite(s)", suites.size());
    }

    private void evaluate(UUID sourceSuiteId, DeploymentReferenceDto targetRef, String cloneSuffix) {
        log.info("--- Suite {} ---", sourceSuiteId);

        // Step 1: Clone (or reuse)
        final UUID cloneId = cloneService.resolveClone(sourceSuiteId, cloneSuffix);

        // Step 2: Fetch — cloneId carried explicitly as the destination, not re-resolved
        final SuiteFetchBundle bundle = fetchService.fetch(sourceSuiteId, cloneId);

        // Step 3: Run
        final File csvFile = runOrchestrationService.run(bundle, targetRef, cliProperties.getWorkDir());

        // Step 4: Import into the clone resolved in step 1 (cloneId, not bundle.getDestinationSuiteId() —
        // they are the same here but naming makes the intent explicit)
        final TestSuiteRunResponseDto run = importService.importResults(cloneId, csvFile);

        log.info(
                "Suite {} evaluation complete — imported run {} (status={}) into clone {}",
                sourceSuiteId,
                run.getId(),
                run.getStatus(),
                cloneId);
    }
}
