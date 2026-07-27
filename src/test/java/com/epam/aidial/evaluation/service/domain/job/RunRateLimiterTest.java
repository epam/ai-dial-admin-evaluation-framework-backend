package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunRateLimiter")
class RunRateLimiterTest {

    @Test
    @DisplayName("a null rateLimitRps yields a disabled gate whose acquire is a no-op")
    void nullRpsIsDisabled() {
        RunRateLimiter limiter = RunRateLimiter.of(null);

        assertThat(limiter.isEnabled()).isFalse();
        assertThatCode(limiter::acquire).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a non-positive rateLimitRps yields a disabled gate")
    void nonPositiveRpsIsDisabled() {
        assertThat(RunRateLimiter.of(0.0).isEnabled()).isFalse();
        assertThat(RunRateLimiter.of(-1.0).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a configured rateLimitRps yields an enabled gate")
    void configuredRpsIsEnabled() {
        assertThat(RunRateLimiter.of(5.0).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a disabled gate admits far more calls than any RPS would, confirming it never throttles")
    void disabledGateDoesNotThrottle() throws InterruptedException {
        RunRateLimiter limiter = RunRateLimiter.disabled();

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1000);
    }

    @Test
    @DisplayName("an enabled gate throttles beyond its burst capacity")
    void enabledGateThrottlesBeyondCapacity() throws InterruptedException {
        // Capacity 2 tokens/second: the first two acquires drain the bucket, the third must wait for a refill.
        RunRateLimiter limiter = RunRateLimiter.of(2.0);
        limiter.acquire();
        limiter.acquire();

        long start = System.nanoTime();
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThan(100);
    }

    @Test
    @DisplayName("tryAcquire returns true and consumes a token when one is available")
    void tryAcquireSucceedsWhenTokenAvailable() {
        assertThat(RunRateLimiter.of(2.0).tryAcquire()).isTrue();
        assertThat(RunRateLimiter.disabled().tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("tryAcquire reports an interrupted wait as false and re-sets the interrupt flag")
    void tryAcquireReportsInterruptAndPreservesFlag() throws InterruptedException {
        // A 1 rps bucket drained to empty makes the next acquisition block; interrupting first means the
        // blocking wait is entered already-interrupted.
        RunRateLimiter limiter = RunRateLimiter.of(1.0);
        limiter.acquire();

        boolean acquired;
        boolean flagSet;
        Thread.currentThread().interrupt();
        try {
            acquired = limiter.tryAcquire();
            // The flag MUST survive: InProcessEvaluationExecutor reads it to decide the cancelled case
            // contributes no result row.
            flagSet = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        assertThat(acquired).isFalse();
        assertThat(flagSet).isTrue();
    }

    @Test
    @DisplayName("a pending token wait is interruptible, so run cancellation is not delayed")
    void pendingWaitIsInterruptible() throws InterruptedException {
        // A 1 rps bucket drained to empty makes the next acquire block for roughly a second.
        RunRateLimiter limiter = RunRateLimiter.of(1.0);
        limiter.acquire();

        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean callIssued = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                limiter.acquire();
                callIssued.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                finished.countDown();
            }
        });
        worker.start();

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        worker.interrupt();

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(callIssued)
                .as("the worker must terminate without issuing its call")
                .isFalse();
    }
}
