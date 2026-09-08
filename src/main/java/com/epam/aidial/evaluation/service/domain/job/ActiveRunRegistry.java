package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
public class ActiveRunRegistry {

    private final ConcurrentHashMap<UUID, RunHandle> handles = new ConcurrentHashMap<>();

    /** Registers a new {@link RunHandle} for the given run id, replacing any previous handle for it. */
    public RunHandle register(UUID runId) {
        RunHandle handle = new RunHandle();
        handles.put(runId, handle);
        return handle;
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

    public Optional<RunHandle> find(UUID runId) {
        return Optional.ofNullable(handles.get(runId));
    }
}
