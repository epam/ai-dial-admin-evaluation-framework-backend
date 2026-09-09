package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Configures the shared test suite run job executor: one thread per dispatched run
 * ("test-suite-run-N"), thread mode following {@link RunExecutorFactory#isVirtualThreads()} (in turn
 * driven by {@code spring.threads.virtual.enabled}).
 */
@Configuration
@LogExecution
public class AsyncConfiguration {

    /**
     * Thread-per-run executor with no concurrency limit: admission is already enforced by
     * {@code TestSuiteRunService.enforceConcurrencyLimits} (HTTP 429) before dispatch, and a
     * {@code SimpleAsyncTaskExecutor} concurrency limit would instead *block* the caller — here the HTTP
     * thread running inside {@code afterCommit} — rather than reject. Rejection therefore only happens
     * after {@link SimpleAsyncTaskExecutor#close()} runs at container shutdown
     * ({@code TaskRejectedException} extends {@code RejectedExecutionException}), which is exactly the
     * existing {@code EXECUTOR_REJECTED} dispatch-compensation path.
     *
     * <p>{@code setCancelRemainingTasksOnClose(true)} makes {@code close()} interrupt every tracked
     * active job thread immediately, the same effect the previous pool's {@code shutdownNow()} had at
     * context close — see the interrupt-flag comments in {@code TestSuiteEvaluationJob.run}'s catch
     * blocks. {@code setDaemon(true)} only matters in platform-thread mode, so a leftover job thread never
     * blocks JVM exit.
     */
    @Bean(name = "testSuiteRunExecutor")
    public AsyncTaskExecutor testSuiteRunExecutor(RunExecutorFactory runExecutorFactory) {
        var executor = new SimpleAsyncTaskExecutor("test-suite-run-");
        executor.setVirtualThreads(runExecutorFactory.isVirtualThreads());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setCancelRemainingTasksOnClose(true);
        executor.setDaemon(true);
        return executor;
    }
}
