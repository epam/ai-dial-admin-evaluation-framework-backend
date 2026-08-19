package com.epam.aidial.evaluation.cli.command;

import java.util.List;
import java.util.UUID;
import picocli.CommandLine.Option;

/**
 * Picocli mixin for the {@code --suites} option shared by every command that processes a set of
 * source suites.
 *
 * <p>Required, with no configuration/env-var fallback: which suites to process is always a
 * per-invocation, CI-log-visible choice, never a stable environment default.
 */
public class SuitesOption {

    @Option(
            names = {"--suites"},
            split = ",",
            required = true,
            description = "Comma-separated source EF test-suite UUIDs to process.")
    private List<UUID> suites;

    public List<UUID> resolve() {
        return suites;
    }
}
