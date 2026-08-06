package com.epam.aidial.evaluation.cli;

import com.epam.aidial.evaluation.cli.command.RootCommand;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Spring Boot entry point for the {@code eval-cli} standalone CLI tool.
 *
 * <p>Uses {@code picocli-spring-boot-starter}'s {@link IFactory} integration so all
 * picocli {@code @Command} classes are instantiated through the Spring application context,
 * enabling full dependency injection.
 *
 * <p>{@link ConfigurationPropertiesScan} registers this module's own {@code @ConfigurationProperties}
 * classes ({@code SourceProperties}, {@code EvalCliProperties}) as beans; a bare
 * {@code @EnableConfigurationProperties} (no class list) registers nothing by itself — it only appeared
 * to work in tests because each test class explicitly listed those classes itself.
 *
 * <p>Exits with the picocli command's return code so callers (e.g., CI scripts) can detect failures.
 */
@LogExecution
@SpringBootApplication
@ConfigurationPropertiesScan
public class EvalCliApplication implements CommandLineRunner, ExitCodeGenerator {

    private final IFactory picocliFactory;
    private final RootCommand rootCommand;

    private int exitCode;

    public EvalCliApplication(IFactory picocliFactory, RootCommand rootCommand) {
        this.picocliFactory = picocliFactory;
        this.rootCommand = rootCommand;
    }

    @Override
    public void run(String... args) {
        exitCode = new CommandLine(rootCommand, picocliFactory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(EvalCliApplication.class, args)));
    }
}
