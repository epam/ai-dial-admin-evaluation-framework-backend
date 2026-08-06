package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * Root CLI command that groups all subcommands.
 *
 * <p>Subcommands are registered via the {@code subcommands} attribute. Picocli's Spring Boot starter
 * discovers and wires them via the Spring {@code IFactory}.
 */
@Component
@LogExecution
@Command(
        name = "eval-cli",
        description = "Evaluation CLI — clone, fetch, run, and import test-case results across environments.",
        mixinStandardHelpOptions = true,
        subcommands = {
            CloneCommand.class,
            FetchCommand.class,
            RunCommand.class,
            ImportCommand.class,
            EvaluateCommand.class
        })
public class RootCommand implements Runnable {

    @Override
    public void run() {
        // No-op: picocli prints usage when no subcommand is given (handled by framework)
    }
}
