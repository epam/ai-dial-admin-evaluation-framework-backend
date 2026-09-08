package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunExecutorFactory")
class RunExecutorFactoryTest {

    private static final long AWAIT_TIMEOUT_MS = 5000L;
    private static final long BLOCKING_SLEEP_MS = 60_000L;

    @Test
    @DisplayName("isVirtualThreads reflects the constructor argument")
    void isVirtualThreads_reflectsConstructorArgument() {
        assertThat(new RunExecutorFactory(true).isVirtualThreads()).isTrue();
        assertThat(new RunExecutorFactory(false).isVirtualThreads()).isFalse();
    }

    @Test
    @DisplayName("newWorkerExecutor returns a distinct executor instance on every call")
    void newWorkerExecutor_returnsDistinctInstancesPerCall() {
        RunExecutorFactory factory = new RunExecutorFactory(true);
        ExecutorService first = factory.newWorkerExecutor();
        ExecutorService second = factory.newWorkerExecutor();
        try {
            assertThat(first).isNotSameAs(second);
        } finally {
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    @DisplayName("virtual mode runs tasks on a virtual thread named run-worker-*")
    void virtualMode_runsTasksOnVirtualThreadWithExpectedName() throws Exception {
        RunExecutorFactory factory = new RunExecutorFactory(true);
        ExecutorService executor = factory.newWorkerExecutor();
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicReference<String> threadName = new AtomicReference<>();
        try {
            executor.submit(() -> {
                        virtual.set(Thread.currentThread().isVirtual());
                        threadName.set(Thread.currentThread().getName());
                    })
                    .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(virtual).isTrue();
        assertThat(threadName.get()).startsWith("run-worker-");
    }

    @Test
    @DisplayName("platform mode runs tasks on a daemon platform thread named run-worker-*")
    void platformMode_runsTasksOnDaemonPlatformThreadWithExpectedName() throws Exception {
        RunExecutorFactory factory = new RunExecutorFactory(false);
        ExecutorService executor = factory.newWorkerExecutor();
        AtomicBoolean virtual = new AtomicBoolean(true);
        AtomicBoolean daemon = new AtomicBoolean();
        AtomicReference<String> threadName = new AtomicReference<>();
        try {
            executor.submit(() -> {
                        virtual.set(Thread.currentThread().isVirtual());
                        daemon.set(Thread.currentThread().isDaemon());
                        threadName.set(Thread.currentThread().getName());
                    })
                    .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(virtual).isFalse();
        assertThat(daemon).isTrue();
        assertThat(threadName.get()).startsWith("run-worker-");
    }

    @Test
    @DisplayName("virtual mode: shutdownNow interrupts a blocked task and rejects further submissions")
    void virtualMode_shutdownNowInterruptsBlockedTaskAndRejectsFurtherSubmissions() throws Exception {
        assertShutdownNowInterruptsBlockedTaskAndRejectsFurtherSubmissions(new RunExecutorFactory(true));
    }

    @Test
    @DisplayName("platform mode: shutdownNow interrupts a blocked task and rejects further submissions")
    void platformMode_shutdownNowInterruptsBlockedTaskAndRejectsFurtherSubmissions() throws Exception {
        assertShutdownNowInterruptsBlockedTaskAndRejectsFurtherSubmissions(new RunExecutorFactory(false));
    }

    private void assertShutdownNowInterruptsBlockedTaskAndRejectsFurtherSubmissions(RunExecutorFactory factory)
            throws Exception {
        ExecutorService executor = factory.newWorkerExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            Future<?> future = executor.submit(() -> {
                taskStarted.countDown();
                try {
                    Thread.sleep(BLOCKING_SLEEP_MS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            });

            assertThat(taskStarted.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .as("task started")
                    .isTrue();

            executor.shutdownNow();

            future.get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertThat(interrupted).as("task observed InterruptedException").isTrue();

            assertThatThrownBy(() -> executor.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
        } finally {
            executor.shutdownNow();
        }
    }
}
