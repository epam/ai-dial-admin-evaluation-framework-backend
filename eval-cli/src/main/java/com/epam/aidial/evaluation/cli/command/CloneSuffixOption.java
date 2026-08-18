package com.epam.aidial.evaluation.cli.command;

import picocli.CommandLine.Option;

/**
 * Picocli mixin for the {@code --clone-suffix} option shared by commands that resolve a destination
 * clone: the cloned suite is named {@code <sourceSuiteName>_<cloneSuffix>}.
 *
 * <p>Required, with no configuration/env-var fallback — consistent with {@code --suites}.
 */
public class CloneSuffixOption {

    @Option(
            names = {"--clone-suffix"},
            required = true,
            description = "Suffix appended to cloned suite names: <sourceSuiteName>_<suffix>.")
    private String cloneSuffix;

    public String resolve() {
        return cloneSuffix;
    }
}
