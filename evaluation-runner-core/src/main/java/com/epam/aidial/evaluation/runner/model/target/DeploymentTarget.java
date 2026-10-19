package com.epam.aidial.evaluation.runner.model.target;

import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;

/**
 * Deployment HTTP target. The component is not null-checked (trusted persisted data; request validation lives
 * elsewhere).
 */
public record DeploymentTarget(DeploymentReferenceDto ref) implements HttpTarget {

    @Override
    public SuiteType type() {
        return SuiteType.DEPLOYMENT;
    }
}
