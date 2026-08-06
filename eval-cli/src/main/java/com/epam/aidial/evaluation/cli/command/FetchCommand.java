package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.cli.service.CloneService;
import com.epam.aidial.evaluation.cli.service.FetchService;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Fetches suite configuration and all test cases from the source EF for each selected suite,
 * persisting the result as a JSON bundle under {@code cli.workDir}.
 *
 * <p>Also resolves the destination clone ID (via {@link CloneService}) so the bundle is fully
 * self-contained for a subsequent standalone {@code run} invocation.
 *
 * <p>Delegates entirely to {@link FetchService}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@Command(
        name = "fetch",
        description = "Fetch suite configuration and all test cases from the source EF for each selected"
                + " suite, persisting the result under cli.workDir.")
public class FetchCommand implements Runnable {

    @Mixin
    private SuitesOption suitesOption;

    @Mixin
    private CloneSuffixOption cloneSuffixOption;

    private final CloneService cloneService;
    private final FetchService fetchService;
    private final EvalCliProperties cliProperties;

    @Override
    public void run() {
        final List<UUID> suites = suitesOption.resolve();
        final String cloneSuffix = cloneSuffixOption.resolve();

        log.info("Fetching {} selected suite(s)", suites.size());
        for (UUID sourceSuiteId : suites) {
            final UUID destinationSuiteId = cloneService.resolveClone(sourceSuiteId, cloneSuffix);
            final SuiteFetchBundle bundle = fetchService.fetch(sourceSuiteId, destinationSuiteId);
            log.info(
                    "Fetched suite '{}' ({}) — {} test case(s)",
                    bundle.getSuite().getName(),
                    sourceSuiteId,
                    bundle.getTestCases().size());
        }
        log.info("Fetch step complete");
    }
}
