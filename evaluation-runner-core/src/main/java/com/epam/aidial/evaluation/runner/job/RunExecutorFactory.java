package com.epam.aidial.evaluation.runner.job;

import io.opentelemetry.context.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Single source of the run thread mode: whether a test suite run's worker executors (and, in the EF
 * backend, the run job executor) use virtual or platform threads.
 *
 * <p>Virtual threads are the default. Setting {@code spring.threads.virtual.enabled=false}
 * ({@code VIRTUAL_THREADS_ENABLED}) switches every run's worker executor — and, in the EF backend, the
 * run job executor — to platform threads, so a sampling profiler can attribute CPU time to real OS
 * threads instead of the profiler-invisible carrier threads virtual threads run on.
 *
 * <p>Both modes use a thread-per-task executor so {@link ExecutorService#shutdownNow()} semantics
 * (interrupt every live task, reject new submissions) are identical regardless of thread mode — callers
 * that cancel a run never need a mode-specific branch. The returned executor wraps task submission with
 * {@link Context#taskWrapping(ExecutorService)} so the current OpenTelemetry context propagates into
 * worker threads.
 */
public final class RunExecutorFactory {

    private static final String WORKER_THREAD_NAME_PREFIX = "run-worker-";

    private final boolean virtualThreads;

    public RunExecutorFactory(boolean virtualThreads) {
        this.virtualThreads = virtualThreads;
    }

    public boolean isVirtualThreads() {
        return virtualThreads;
    }

    /**
     * Creates a new thread-per-task worker executor for one run. Callers must create one instance per
     * run and shut it down (typically via {@code shutdownNow()}) when the run completes or is cancelled.
     */
    public ExecutorService newWorkerExecutor() {
        ThreadFactory threadFactory = virtualThreads
                ? Thread.ofVirtual().name(WORKER_THREAD_NAME_PREFIX, 0).factory()
                : Thread.ofPlatform()
                        .daemon(true)
                        .name(WORKER_THREAD_NAME_PREFIX, 0)
                        .factory();
        return Context.taskWrapping(Executors.newThreadPerTaskExecutor(threadFactory));
    }
}
