package com.epam.aidial.evaluation.cli.command;

import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import picocli.CommandLine.Option;

/**
 * Picocli mixin for the {@code --deployment-id} option shared by {@code run} and {@code evaluate}.
 *
 * <p>Optional: when omitted, {@code RunOrchestrationService} falls back to the fetched suite's own
 * recorded {@code deploymentRef} instead of failing argument parsing.
 */
public class DeploymentIdOption {

    @Option(
            names = {"--deployment-id"},
            description = "Target deployment ID to send requests to (overrides the suite's recorded ref)."
                    + " If omitted, the suite's own recorded deployment ref is used.")
    private String deploymentId;

    public DeploymentReferenceDto resolve() {
        return deploymentId != null
                ? DeploymentReferenceDto.builder()
                        .id(deploymentId)
                        .name(deploymentId)
                        .build()
                : null;
    }
}
