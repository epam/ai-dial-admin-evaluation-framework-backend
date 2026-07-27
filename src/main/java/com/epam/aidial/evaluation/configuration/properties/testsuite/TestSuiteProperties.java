package com.epam.aidial.evaluation.configuration.properties.testsuite;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Suite-configuration limits. Distinct from {@code test-suite-run} ({@link TestSuiteRunProperties}),
 * which configures how a run executes; these bound what a suite may be configured to contain.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "test-suite")
public class TestSuiteProperties {

    @Valid
    private MultiRequest multiRequest = new MultiRequest();

    @Getter
    @Setter
    public static class MultiRequest {

        /**
         * Maximum number of requests in a suite's normalized chain (request 0 plus every
         * {@code additionalRequests} element). Enforced at suite save (HTTP 400) <b>and again</b> at run
         * creation (HTTP 409): because the cap is configurable it may be lowered after a suite is
         * persisted, so a save-time check alone would let an over-cap suite run.
         */
        @Min(1)
        private int maxRequests;
    }
}
