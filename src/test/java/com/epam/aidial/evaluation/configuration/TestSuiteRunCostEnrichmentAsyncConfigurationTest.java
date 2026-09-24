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

@DisplayName("TestSuiteRunCostEnrichmentAsyncConfiguration.testSuiteRunCostEnrichmentExecutor")
class TestSuiteRunCostEnrichmentAsyncConfigurationTest {

    private final TestSuiteRunCostEnrichmentAsyncConfiguration configuration =
            new TestSuiteRunCostEnrichmentAsyncConfiguration();

    private record ThreadInfo(boolean virtual, boolean daemon, String name) {}

    private static ThreadInfo captureThreadInfo() {
        final Thread thread = Thread.currentThread();
        return new ThreadInfo(thread.isVirtual(), thread.isDaemon(), thread.getName());
    }

    @Test
    @DisplayName(
            "virtual thread mode: a submitted task runs on a virtual thread named test-suite-run-cost-enrichment-*")
    void virtualModeRunsOnVirtualThread() throws Exception {
        final AsyncTaskExecutor executor =
                configuration.testSuiteRunCostEnrichmentExecutor(new RunExecutorFactory(true));

        final ThreadInfo info = CompletableFuture.supplyAsync(
                        TestSuiteRunCostEnrichmentAsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isTrue();
        assertThat(info.name()).startsWith("test-suite-run-cost-enrichment-");
    }

    @Test
    @DisplayName("platform thread mode: a submitted task runs on a daemon platform thread named"
            + " test-suite-run-cost-enrichment-*")
    void platformModeRunsOnDaemonPlatformThread() throws Exception {
        final AsyncTaskExecutor executor =
                configuration.testSuiteRunCostEnrichmentExecutor(new RunExecutorFactory(false));

        final ThreadInfo info = CompletableFuture.supplyAsync(
                        TestSuiteRunCostEnrichmentAsyncConfigurationTest::captureThreadInfo, executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(info.virtual()).isFalse();
        assertThat(info.daemon()).isTrue();
        assertThat(info.name()).startsWith("test-suite-run-cost-enrichment-");
    }

    @Test
    @DisplayName("close() rejects new task submissions")
    void closeRejectsNewSubmissions() {
        final SimpleAsyncTaskExecutor executor = (SimpleAsyncTaskExecutor)
                configuration.testSuiteRunCostEnrichmentExecutor(new RunExecutorFactory(true));

        executor.close();

        assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("close() interrupts a task blocked in Thread.sleep")
    void closeInterruptsBlockedTask() throws Exception {
        final SimpleAsyncTaskExecutor executor = (SimpleAsyncTaskExecutor)
                configuration.testSuiteRunCostEnrichmentExecutor(new RunExecutorFactory(true));
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
}
