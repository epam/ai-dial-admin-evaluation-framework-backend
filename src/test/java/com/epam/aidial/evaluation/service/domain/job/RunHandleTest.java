package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunHandle")
class RunHandleTest {

    @Test
    @DisplayName("cancel() marks the handle as cancelled")
    void cancelMarksCancelled() {
        RunHandle handle = new RunHandle();

        handle.cancel();

        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("throwIfCancelled throws CancellationException once cancelled")
    void throwIfCancelledThrowsAfterCancel() {
        RunHandle handle = new RunHandle();
        handle.cancel();

        assertThatThrownBy(handle::throwIfCancelled).isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("throwIfCancelled is a no-op before cancel")
    void throwIfCancelledNoopBeforeCancel() {
        RunHandle handle = new RunHandle();

        assertThatCode(handle::throwIfCancelled).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("executor().execute rejects new tasks after cancel")
    void executorRejectsAfterCancel() {
        RunHandle handle = new RunHandle();
        handle.cancel();

        assertThatThrownBy(() -> handle.executor().execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("close() returns promptly even while a non-interruptible task is busy-looping")
    void closeReturnsPromptlyWithBusyTaskInFlight() throws InterruptedException {
        RunHandle handle = new RunHandle();
        CountDownLatch taskStarted = new CountDownLatch(1);
        BusyFlag busyFlag = new BusyFlag();

        handle.executor().execute(() -> {
            taskStarted.countDown();
            // Non-interruptible busy loop: ignores the executor's interrupt and only stops once the
            // volatile flag is flipped by the test, after close() has already returned.
            while (!busyFlag.get()) {
                // busy-wait
            }
        });
        assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        handle.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(1000);

        // Let the busy-looping task exit so it doesn't leak past the test.
        busyFlag.set(true);
    }

    @Test
    @DisplayName("close() alone does not mark the handle as cancelled")
    void closeAloneDoesNotSetCancelled() {
        RunHandle handle = new RunHandle();

        handle.close();

        assertThat(handle.isCancelled()).isFalse();
    }

    /** Lets the busy-loop lambda in a test read/write a volatile flag set from the test thread. */
    private static final class BusyFlag {
        private volatile boolean flag;

        boolean get() {
            return flag;
        }

        void set(boolean value) {
            flag = value;
        }
    }
}
