package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Per-JVM registry of {@link RunHandle}s for currently dispatched test suite runs. Replaces the previous
 * {@code ConcurrentHashMap<UUID, AtomicBoolean>} cancellation-signal map (see {@code design.md} decision
 * D1). Multi-instance deployments: a cancel served by another instance finds no handle here and only
 * writes {@code CANCELLING}; the owning instance finalizes {@code CANCELLED} when its job completes.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ActiveRunRegistry {

    private final ConcurrentHashMap<UUID, RunHandle> handles = new ConcurrentHashMap<>();

    private final RunExecutorFactory runExecutorFactory;

    /** Registers a new {@link RunHandle} for the given run id, replacing any previous handle for it. */
    public RunHandle register(UUID runId) {
        RunHandle handle = new RunHandle(runExecutorFactory.newWorkerExecutor());
        handles.put(runId, handle);
        return handle;
    }

    /**
     * Atomically registers a new {@link RunHandle} for the given run id, but only if no handle is
     * already registered. Returns an empty Optional if a handle is already registered (indicating
     * the run is already active/dispatched and a concurrent trigger attempt was made), or an
     * Optional containing the newly-registered handle on success.
     */
    public Optional<RunHandle> registerIfAbsent(UUID runId) {
        RunHandle newHandle = new RunHandle(runExecutorFactory.newWorkerExecutor());
        RunHandle existing = handles.putIfAbsent(runId, newHandle);
        if (existing != null) {
            // A handle is already registered. Close the one we just created since it will not be used.
            newHandle.close();
            return Optional.empty();
        }
        return Optional.of(newHandle);
    }

    /** Cancels the run's handle, if one is registered on this instance. No-op otherwise. */
    public void cancel(UUID runId) {
        RunHandle handle = handles.get(runId);
        if (handle == null) {
            log.debug("No active run handle for run {}; cancel is a no-op on this instance", runId);
            return;
        }
        handle.cancel();
    }

    /** Removes the run's handle. Called once the run's job has finished, in its {@code finally} block. */
    public void remove(UUID runId) {
        handles.remove(runId);
    }

    /** Number of runs currently in flight on this instance (registered but not yet removed). */
    public int activeCount() {
        return handles.size();
    }
}
