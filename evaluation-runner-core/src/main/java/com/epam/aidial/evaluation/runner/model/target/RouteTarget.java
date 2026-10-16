package com.epam.aidial.evaluation.runner.model.target;

import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;

/**
 * Global DIAL Core Route HTTP target. The component is not null-checked (trusted persisted data; request
 * validation lives elsewhere).
 */
public record RouteTarget(RouteReferenceDto ref) implements HttpTarget {

    @Override
    public SuiteType type() {
        return SuiteType.ROUTE;
    }
}
