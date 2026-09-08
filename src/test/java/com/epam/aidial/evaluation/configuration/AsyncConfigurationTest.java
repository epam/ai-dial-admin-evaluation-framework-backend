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
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@DisplayName("AsyncConfiguration.testSuiteRunExecutor")
class AsyncConfigurationTest {

    private final AsyncConfiguration configuration = new AsyncConfiguration();

    private record ThreadInfo(boolean virtual, boolean daemon, String name) {}

    private static ThreadInfo captureThreadInfo() {
        Thread thread = Thread.currentThread();
        return new ThreadInfo(thread.isVirtual(), thread.isDaemon(), thread.getName());
    }

    @Test
    @DisplayName("virtual thread mode: a submitted task runs on a virtual thread named test-suite-run-*")
    void virtualModeRunsOnVirtualThread() throws Exception {
        AsyncTaskExecutor executor = configuration.testSuiteRunExecutor(new RunExecutorFactory(true));

        ThreadInfo info = CompletableFuture.supplyAsync(AsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isTrue();
        assertThat(info.name()).startsWith("test-suite-run-");
    }

    @Test
    @DisplayName("platform thread mode: a submitted task runs on a daemon platform thread named test-suite-run-*")
    void platformModeRunsOnDaemonPlatformThread() throws Exception {
        AsyncTaskExecutor executor = configuration.testSuiteRunExecutor(new RunExecutorFactory(false));

        ThreadInfo info = CompletableFuture.supplyAsync(AsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isFalse();
        assertThat(info.daemon()).isTrue();
        assertThat(info.name()).startsWith("test-suite-run-");
    }

    @Test
    @DisplayName("close() rejects new task submissions")
    void closeRejectsNewSubmissions() {
        SimpleAsyncTaskExecutor executor =
                (SimpleAsyncTaskExecutor) configuration.testSuiteRunExecutor(new RunExecutorFactory(true));

        executor.close();

        assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("close() interrupts a task blocked in Thread.sleep")
    void closeInterruptsBlockedTask() throws Exception {
        SimpleAsyncTaskExecutor executor =
                (SimpleAsyncTaskExecutor) configuration.testSuiteRunExecutor(new RunExecutorFactory(true));
        CountDownLatch taskStarted = new CountDownLatch(1);
        CompletableFuture<Boolean> observedInterrupt = new CompletableFuture<>();

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
}
