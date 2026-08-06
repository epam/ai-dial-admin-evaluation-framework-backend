package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.service.CloneService;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Clones each selected source suite to {@code <name>_<suffix>} on the source EF, reusing an existing
 * clone if one already exists.
 *
 * <p>Delegates entirely to {@link CloneService}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@Command(
        name = "clone",
        description = "For each selected source suite, ensure a clone named <name>_<suffix> exists on the"
                + " source EF. Reuses an existing clone if present; creates one otherwise.")
public class CloneCommand implements Runnable {

    @Mixin
    private SuitesOption suitesOption;

    @Mixin
    private CloneSuffixOption cloneSuffixOption;

    private final CloneService cloneService;
    private final EvalCliProperties cliProperties;

    @Override
    public void run() {
        final List<UUID> suites = suitesOption.resolve();
        final String cloneSuffix = cloneSuffixOption.resolve();

        log.info("Cloning {} selected suite(s) with suffix '{}'", suites.size(), cloneSuffix);
        final Map<UUID, UUID> clones = cloneService.resolveClones(suites, cloneSuffix);
        clones.forEach((srcId, cloneId) -> log.info("  suite {} → clone {}", srcId, cloneId));
        log.info("Clone step complete — {} suite(s) resolved", clones.size());
    }
}
