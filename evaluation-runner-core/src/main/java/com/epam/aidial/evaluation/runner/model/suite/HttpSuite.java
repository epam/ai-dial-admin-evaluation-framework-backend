package com.epam.aidial.evaluation.runner.model.suite;

import com.epam.aidial.evaluation.runner.model.target.HttpTarget;
import java.util.List;

/**
 * A suite invoked over HTTP (deployment or route) through a request chain. Components are not null-checked
 * (trusted persisted data; request validation lives elsewhere).
 *
 * @param target the HTTP target every request of the chain is sent to
 * @param chain ordered request chain; {@code chain.get(0)} is request #0 (the suite's own request fields),
 *     the rest are the suite's {@code additionalRequests} in order
 */
public record HttpSuite(HttpTarget target, List<RequestExecutionSpec> chain) implements SuiteDefinition {

    /** Request #0 of the chain. */
    public RequestExecutionSpec first() {
        return chain.get(0);
    }

    /** Returns a copy of this suite calling {@code newTarget} instead, with the same chain. */
    public HttpSuite withTarget(HttpTarget newTarget) {
        return new HttpSuite(newTarget, chain);
    }
}
