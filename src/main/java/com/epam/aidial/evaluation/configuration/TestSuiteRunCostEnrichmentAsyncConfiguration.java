package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.configuration.properties.query.QueryDslTestSuiteRunCostEnrichmentProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Conditional executor dedicated to the {@code total_cost} result-page enrichment lookup (design D4 of
 * {@code enrich-test-suite-runs-total-cost}). Thread mode follows
 * {@link RunExecutorFactory#isVirtualThreads()} — the same switch the run job executor
 * ({@link AsyncConfiguration#testSuiteRunExecutor}) follows — but this executor is feature-specific: it
 * is never shared with unrelated background work, so its shutdown/cancellation semantics stay
 * unambiguous under test.
 *
 * <p>{@code setCancelRemainingTasksOnClose(true)} interrupts any in-flight lookup at container
 * shutdown, mirroring {@code testSuiteRunExecutor}. {@code setDaemon(true)} only matters in
 * platform-thread mode, so a leftover lookup task never blocks JVM exit.
 *
 * <p>Unlike {@code testSuiteRunExecutor}, no {@code ContextPropagatingTaskDecorator} is installed here:
 * the caller credential and OpenTelemetry context are captured and wrapped explicitly at the
 * submission call site (see {@code TotalCostTestSuiteRunsPageExtender}), not inherited implicitly from
 * the executor.
 */
@Configuration
@LogExecution
@ConditionalOnProperty(
        prefix = QueryDslTestSuiteRunCostEnrichmentProperties.PREFIX,
        name = "enabled",
        havingValue = "true")
public class TestSuiteRunCostEnrichmentAsyncConfiguration {

    private static final String THREAD_NAME_PREFIX = "test-suite-run-cost-enrichment-";

    @Bean(name = "testSuiteRunCostEnrichmentExecutor")
    public AsyncTaskExecutor testSuiteRunCostEnrichmentExecutor(RunExecutorFactory runExecutorFactory) {
        var executor = new SimpleAsyncTaskExecutor(THREAD_NAME_PREFIX);
        executor.setVirtualThreads(runExecutorFactory.isVirtualThreads());
        executor.setDaemon(true);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }
}
