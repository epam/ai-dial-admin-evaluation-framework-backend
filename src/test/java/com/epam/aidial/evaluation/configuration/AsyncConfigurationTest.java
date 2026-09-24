package com.epam.aidial.evaluation.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

@DisplayName("AsyncConfiguration")
class AsyncConfigurationTest {

    private final AsyncConfiguration configuration = new AsyncConfiguration();

    private record ThreadInfo(boolean virtual, boolean daemon, String name) {}

    private static ThreadInfo captureThreadInfo() {
        final Thread thread = Thread.currentThread();
        return new ThreadInfo(thread.isVirtual(), thread.isDaemon(), thread.getName());
    }

    @Test
    @DisplayName("virtual thread mode: a submitted task runs on a virtual thread named test-suite-run-*")
    void virtualModeRunsOnVirtualThread() throws Exception {
        final AsyncTaskExecutor executor = configuration.testSuiteRunExecutor(new RunExecutorFactory(true));

        final ThreadInfo info = CompletableFuture.supplyAsync(AsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isTrue();
        assertThat(info.name()).startsWith("test-suite-run-");
    }

    @Test
    @DisplayName("platform thread mode: a submitted task runs on a daemon platform thread named test-suite-run-*")
    void platformModeRunsOnDaemonPlatformThread() throws Exception {
        final AsyncTaskExecutor executor = configuration.testSuiteRunExecutor(new RunExecutorFactory(false));

        final ThreadInfo info = CompletableFuture.supplyAsync(AsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isFalse();
        assertThat(info.daemon()).isTrue();
        assertThat(info.name()).startsWith("test-suite-run-");
    }

    @Test
    @DisplayName("close() rejects new task submissions")
    void closeRejectsNewSubmissions() {
        final SimpleAsyncTaskExecutor executor =
                (SimpleAsyncTaskExecutor) configuration.testSuiteRunExecutor(new RunExecutorFactory(true));

        executor.close();

        assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("close() interrupts a task blocked in Thread.sleep")
    void closeInterruptsBlockedTask() throws Exception {
        final SimpleAsyncTaskExecutor executor =
                (SimpleAsyncTaskExecutor) configuration.testSuiteRunExecutor(new RunExecutorFactory(true));
        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CompletableFuture<Boolean> observedInterrupt = new CompletableFuture<>();

        executor.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
                observedInterrupt.complete(false);
            } catch (InterruptedException e) {
                observedInterrupt.complete(true);
            }
        });
        assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

        executor.close();

        assertThat(observedInterrupt.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("taskExecutor, virtual thread mode: a submitted task runs on a virtual thread named async-*")
    void taskExecutorVirtualModeRunsOnVirtualThread() throws Exception {
        final AsyncTaskExecutor executor = configuration.taskExecutor(new RunExecutorFactory(true));

        final ThreadInfo info = CompletableFuture.supplyAsync(AsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isTrue();
        assertThat(info.name()).startsWith("async-");
    }

    @Test
    @DisplayName("taskExecutor, platform thread mode: a submitted task runs on a daemon platform thread named async-*")
    void taskExecutorPlatformModeRunsOnDaemonPlatformThread() throws Exception {
        final AsyncTaskExecutor executor = configuration.taskExecutor(new RunExecutorFactory(false));

        final ThreadInfo info = CompletableFuture.supplyAsync(AsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isFalse();
        assertThat(info.daemon()).isTrue();
        assertThat(info.name()).startsWith("async-");
    }

    @Test
    @DisplayName("with several TaskExecutor beans in the context, @Async methods run on taskExecutor")
    void asyncMethodsRunOnTaskExecutorDespiteSeveralExecutors() throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(AsyncTestContext.class)) {
            final ThreadInfo info = context.getBean(AsyncProbe.class).capture().get(5, TimeUnit.SECONDS);

            assertThat(info.name()).startsWith("async-");
        }
    }

    @Configuration
    @EnableAsync
    @Import(AsyncConfiguration.class)
    static class AsyncTestContext {

        @Bean
        RunExecutorFactory runExecutorFactory() {
            return new RunExecutorFactory(true);
        }

        @Bean
        AsyncTaskExecutor otherExecutor() {
            return new SimpleAsyncTaskExecutor("other-");
        }

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    static class AsyncProbe {

        @Async
        public CompletableFuture<ThreadInfo> capture() {
            return CompletableFuture.completedFuture(captureThreadInfo());
        }
    }
}
