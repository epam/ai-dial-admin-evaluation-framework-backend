package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.cli.service.FetchService;
import com.epam.aidial.evaluation.cli.service.ImportService;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.io.File;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Imports previously produced CSV result files into the destination (cloned) suite on the source EF.
 *
 * <p>The results CSV file for each suite is expected at {@code <workDir>/<sourceSuiteId>-results.csv},
 * as produced by the {@code run} step. The destination clone ID is read from the persisted fetch bundle.
 *
 * <p>The source EF's import endpoint automatically triggers Phase 2/3 metric computation — no
 * additional call is issued by this command.
 *
 * <p>Delegates entirely to {@link ImportService}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@Command(
        name = "import",
        description = "Import CSV result files produced by the run step into the destination cloned suite on"
                + " the source EF. Metric computation is triggered automatically by the import"
                + " endpoint.")
public class ImportCommand implements Runnable {

    @Mixin
    private SuitesOption suitesOption;

    private final FetchService fetchService;
    private final ImportService importService;
    private final EvalCliProperties cliProperties;

    @Override
    public void run() {
        final List<UUID> suites = suitesOption.resolve();

        log.info("Importing results for {} selected suite(s)", suites.size());
        for (UUID sourceSuiteId : suites) {
            // Load the fetch bundle to get the destination clone ID
            final SuiteFetchBundle bundle = fetchService.load(sourceSuiteId);
            final File csvFile = csvPath(sourceSuiteId);

            if (!csvFile.exists()) {
                log.error(
                        "Results CSV not found for suite {} — expected at {}. Run the 'run' step first.",
                        sourceSuiteId,
                        csvFile.getAbsolutePath());
                throw new IllegalStateException("Results CSV not found: " + csvFile.getAbsolutePath());
            }

            final TestSuiteRunResponseDto run = importService.importResults(bundle.getDestinationSuiteId(), csvFile);
            log.info(
                    "Imported run {} (status={}) for suite {} → clone {}",
                    run.getId(),
                    run.getStatus(),
                    sourceSuiteId,
                    bundle.getDestinationSuiteId());
        }
        log.info("Import step complete");
    }

    private File csvPath(UUID sourceSuiteId) {
        return new File(cliProperties.getWorkDir(), sourceSuiteId + "-results.csv");
    }
}
