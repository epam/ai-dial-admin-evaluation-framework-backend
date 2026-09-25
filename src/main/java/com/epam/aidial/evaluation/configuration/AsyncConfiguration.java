package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;

/**
 * Configures the shared test suite run job executor — one thread per dispatched run
 * ("test-suite-run-N") — and the default executor for {@code @Async} methods ("async-N"). Both follow
 * {@link RunExecutorFactory#isVirtualThreads()} (in turn driven by {@code spring.threads.virtual.enabled}).
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

    /**
     * Default executor for {@code @Async} methods. Spring resolves it by the reserved bean name
     * {@value AsyncAnnotationBeanPostProcessor#DEFAULT_TASK_EXECUTOR_BEAN_NAME} because the context holds
     * several {@code TaskExecutor}s (this one, {@code testSuiteRunExecutor}, feature-specific executors and
     * the scheduler); without it, Spring falls back to an unmanaged {@code SimpleAsyncTaskExecutor}
     * that ignores the virtual-thread setting and propagates no context. Unbounded, like the fallback it
     * replaces; {@code setCancelRemainingTasksOnClose(true)} interrupts in-flight tasks at container
     * shutdown.
     */
    @Bean(name = AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor taskExecutor(RunExecutorFactory runExecutorFactory) {
        var executor = new SimpleAsyncTaskExecutor("async-");
        executor.setVirtualThreads(runExecutorFactory.isVirtualThreads());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setCancelRemainingTasksOnClose(true);
        executor.setDaemon(true);
        return executor;
    }
}
