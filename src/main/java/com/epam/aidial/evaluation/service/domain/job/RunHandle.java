package com.epam.aidial.evaluation.service.domain.job;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a single test suite run's worker executor for the run's whole lifetime. Not a Spring bean —
 * session-scoped, created and registered by {@link ActiveRunRegistry} once per dispatched run. The
 * executor is supplied by the caller: in production, {@link ActiveRunRegistry} obtains it from
 * {@code RunExecutorFactory.newWorkerExecutor()}, whose thread mode (virtual vs. platform) follows
 * {@code spring.threads.virtual.enabled}.
 *
 * <p>Cancellation is delivered by shutting the executor down ({@link #cancel()} = {@code shutdownNow()})
 * rather than by a flag that phases poll (see {@code design.md} decision D1). {@link #close()} also calls
 * {@code shutdownNow()} — the normal end-of-run teardown path — but deliberately does NOT flip the
 * {@code cancelled} flag, so a run that finished on its own is never reported as cancelled.
 *
 * <p>{@code shutdownNow()}, not {@link ExecutorService#close()}, is used in both paths: {@code close()}'s
 * default implementation waits unboundedly for termination and only escalates to {@code shutdownNow()} if
 * the calling thread is interrupted — but the job thread is never interrupted (design D3), so that wait
 * would be unbounded. {@code shutdownNow()} also reaps per-result-timeout worker orphans, since
 * {@code Future.cancel(true)} alone does not interrupt an already-running task.
 */
public class RunHandle {

    private final ExecutorService executor;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public RunHandle(ExecutorService executor) {
        this.executor = executor;
    }

    public ExecutorService executor() {
        return executor;
    }

    /** Cancels the run: shuts the executor down immediately and marks this handle as cancelled. */
    public void cancel() {
        cancelled.set(true);
        executor.shutdownNow();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws {@link CancellationException} if {@link #cancel()} has been called. Called at phase
     * boundaries so the job's own thread observes cancellation without ever being interrupted itself.
     */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("Run was cancelled");
        }
    }

    /**
     * End-of-run teardown: shuts the executor down immediately. Does NOT mark this handle as cancelled —
     * a run that completed, failed, or was already cancelled explicitly via {@link #cancel()} calls this
     * only to release the executor's resources.
     */
    public void close() {
        executor.shutdownNow();
    }
}
