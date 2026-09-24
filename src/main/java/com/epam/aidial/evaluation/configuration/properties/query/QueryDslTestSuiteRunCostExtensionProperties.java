package com.epam.aidial.evaluation.configuration.properties.query;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configures the opt-in {@code total_cost} result-page extension for row-mode {@code test_suite_runs}
 * structured-query results (design D6 of {@code enrich-test-suite-runs-total-cost}). Disabled by
 * default: enabling it stands up a dedicated short-timeout dial-adas client and lifecycle-managed
 * executor (see {@code TestSuiteRunCostExtensionAsyncConfiguration} and
 * {@code DialAdasClientConfiguration}).
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = QueryDslTestSuiteRunCostExtensionProperties.PREFIX)
public class QueryDslTestSuiteRunCostExtensionProperties {

    /**
     * Single definition of this feature's configuration prefix, reused by every
     * {@code @ConditionalOnProperty(prefix = ..., name = "enabled", havingValue = "true")} gate across
     * {@code TestSuiteRunCostExtensionAsyncConfiguration}, {@code DialAdasClientConfiguration}, and
     * (group 3) {@code TotalCostTestSuiteRunsPageExtender}'s supporting configuration, so the prefix
     * exists exactly once (AGENTS.md: non-configurable constants defined once).
     */
    public static final String PREFIX = "query-dsl.extension.test-suite-run.cost";

    @NotNull
    private Boolean enabled;

    @NotNull
    @Min(1)
    private Integer timeoutSec;
}
