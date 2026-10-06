package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.SseStatusEventDto;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRunSseService {

    /**
     * Cadence of the heartbeat sweep ({@link #sendHeartbeats()}). Deliberately a fixed,
     * non-configurable constant comfortably shorter than the shortest heartbeat interval any caller
     * configures (e.g. the internal DIAL App endpoint's 30s default — see
     * {@code docs/patterns/dial-app-mode.md}), so each emitter's own interval is checked often enough
     * to be honored without a separate per-interval scheduled method.
     */
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 5000L;

    private final TestSuiteRunProperties properties;
    private final Clock clock;

    private final ConcurrentHashMap<String, SseEmitterWrapper> activeEmitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(Set<UUID> runIds, Set<UUID> testSuiteIds, Set<String> statuses) {
        return createEmitterWithHeartbeat(runIds, testSuiteIds, statuses, null);
    }

    /**
     * Creates an SSE emitter with an optional custom heartbeat interval for internal endpoints.
     * When {@code heartbeatIntervalMs} is provided, the emitter will receive heartbeat events
     * at that interval (instead of the default configured interval) to maintain connection liveness
     * for DIAL Core's idle timeout window.
     *
     * @param runIds              filter by run IDs (null/empty = all)
     * @param testSuiteIds        filter by test suite IDs (null/empty = all)
     * @param statuses            filter by statuses (null/empty = all)
     * @param heartbeatIntervalMs optional custom heartbeat interval in milliseconds; if null, uses the
     *                             configured default
     * @return the created SseEmitter
     */
    public SseEmitter createEmitterWithHeartbeat(
            Set<UUID> runIds, Set<UUID> testSuiteIds, Set<String> statuses, Long heartbeatIntervalMs) {
        return createEmitterWithHeartbeat(runIds, testSuiteIds, statuses, heartbeatIntervalMs, null);
    }

    /**
     * Same as {@link #createEmitterWithHeartbeat(Set, Set, Set, Long)}, plus an {@code onDisconnect}
     * callback invoked when the emitter errors out (client/DIAL Core disconnected mid-stream). Used by
     * {@code EvalExecuteInternalController} to cancel the run's eval execution via
     * {@code ActiveRunRegistry.cancel(runId)} on disconnect, mirroring {@code TestSuiteRunService.cancelRun}'s
     * cancellation effect (see {@code docs/patterns/dial-app-mode.md}).
     *
     * @param onDisconnect optional callback invoked once, from the emitter's {@code onError} handler; null
     *                      for callers (e.g. the public status-stream endpoint) that need no such hook
     * @return the created SseEmitter
     */
    public SseEmitter createEmitterWithHeartbeat(
            Set<UUID> runIds,
            Set<UUID> testSuiteIds,
            Set<String> statuses,
            Long heartbeatIntervalMs,
            Runnable onDisconnect) {
        long timeoutMs = properties.getSse().getTimeoutMinutes() * 60L * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        String connectionId = UUID.randomUUID().toString();

        SseEmitterWrapper wrapper = new SseEmitterWrapper(
                connectionId,
                emitter,
                runIds,
                testSuiteIds,
                statuses,
                heartbeatIntervalMs,
                onDisconnect,
                clock.millis());
        activeEmitters.put(connectionId, wrapper);

        emitter.onCompletion(() -> removeEmitter(connectionId));
        emitter.onTimeout(() -> removeEmitter(connectionId));
        emitter.onError(e -> {
            log.debug("SSE connection {} errored out", connectionId, e);
            SseEmitterWrapper removed = removeEmitter(connectionId);
            if (removed != null && removed.getOnDisconnect() != null) {
                removed.getOnDisconnect().run();
            }
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("connectionId", connectionId)));
        } catch (IOException e) {
            log.debug("Failed to send initial SSE connected event for connection {}", connectionId, e);
        }

        return emitter;
    }

    /**
     * Removes a just-created emitter from tracking without relying on {@link SseEmitter}'s own
     * completion callbacks. Those callbacks ({@code onCompletion}/{@code onError}) are only wired to a
     * live servlet async context once Spring MVC hands the emitter off as the controller's response
     * body; a caller that created the emitter via {@link #createEmitterWithHeartbeat} but then failed
     * before returning it as the response (e.g. {@code EvalExecuteInternalController} when
     * {@code TestSuiteEvaluationJob#dispatch} throws) cannot rely on {@code emitter.complete()} to
     * trigger them, since no async context has been attached yet. This method instead removes the
     * emitter directly from this service's tracking, so it is never left registered for a run that was
     * never actually dispatched.
     *
     * @param emitter the emitter returned by a prior {@link #createEmitterWithHeartbeat} call
     */
    public void discardEmitter(SseEmitter emitter) {
        activeEmitters.entrySet().stream()
                .filter(entry -> entry.getValue().getEmitter() == emitter)
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(this::removeEmitter);
    }

    private SseEmitterWrapper removeEmitter(String connectionId) {
        return activeEmitters.remove(connectionId);
    }

    /**
     * Removes the emitter and, if one was registered, runs its {@code onDisconnect} callback — used
     * whenever a write to the emitter fails with {@link IOException}, the same disconnect signal the
     * emitter's own {@code onError} handler reacts to.
     */
    private void handleSendFailure(String connectionId) {
        SseEmitterWrapper removed = removeEmitter(connectionId);
        if (removed != null && removed.getOnDisconnect() != null) {
            removed.getOnDisconnect().run();
        }
    }

    public void notifyStatusUpdate(TestSuiteRun run) {
        SseStatusEventDto event = SseStatusEventDto.builder()
                .runId(run.getId())
                .testSuiteId(run.getTestSuiteId())
                .status(run.getStatus())
                .message("Status changed to " + run.getStatus())
                .timestamp(clock.millis())
                .build();

        long now = clock.millis();
        activeEmitters.values().forEach(wrapper -> {
            if (wrapper.matches(run)) {
                try {
                    wrapper.getEmitter()
                            .send(SseEmitter.event().name("status-update").data(event));
                    wrapper.setLastHeartbeatMs(now);
                } catch (IOException e) {
                    log.debug("Failed to send SSE event to connection {}, removing", wrapper.getConnectionId(), e);
                    handleSendFailure(wrapper.getConnectionId());
                }
            }
        });
    }

    public void notifyProgress(UUID runId, UUID testSuiteId, int completedCases, int totalCases) {
        final Map<String, Object> progressEvent = new LinkedHashMap<>();
        progressEvent.put("runId", runId.toString());
        progressEvent.put("testSuiteId", testSuiteId.toString());
        progressEvent.put("completedCases", completedCases);
        progressEvent.put("totalCases", totalCases);
        progressEvent.put("timestamp", clock.millis());

        long now = clock.millis();
        activeEmitters.values().forEach(wrapper -> {
            try {
                wrapper.getEmitter().send(SseEmitter.event().name("progress").data(progressEvent));
                wrapper.setLastHeartbeatMs(now);
            } catch (IOException e) {
                log.debug("Failed to send progress SSE to connection {}", wrapper.getConnectionId(), e);
                handleSendFailure(wrapper.getConnectionId());
            }
        });
    }

    /**
     * Single heartbeat sweep for every tracked emitter, run on a {@value #HEARTBEAT_CHECK_INTERVAL_MS}ms
     * cadence — far shorter than the slowest configured heartbeat interval — so every emitter's own
     * interval (custom, e.g. the internal DIAL App endpoint's 30s default, or
     * {@code test-suite-run.sse.cleanup-interval-ms} otherwise) is honored without a dedicated scheduled
     * method per cadence. Eviction relies solely on a failed send ({@link IOException}); {@link SseEmitter}
     * already self-times-out from the duration passed at construction (see {@code onTimeout} wired in
     * {@link #createEmitterWithHeartbeat}), so no separate staleness-by-elapsed-time check is needed here.
     */
    @Scheduled(fixedDelay = HEARTBEAT_CHECK_INTERVAL_MS)
    public void sendHeartbeats() {
        long now = clock.millis();
        long defaultIntervalMs = properties.getSse().getCleanupIntervalMs();
        List<String> staleKeys = new ArrayList<>();
        activeEmitters.forEach((connectionId, wrapper) -> {
            long intervalMs =
                    wrapper.getHeartbeatIntervalMs() != null ? wrapper.getHeartbeatIntervalMs() : defaultIntervalMs;
            if (now - wrapper.getLastHeartbeatMs() < intervalMs) {
                return;
            }
            try {
                wrapper.getEmitter().send(SseEmitter.event().name("heartbeat").data("{}", MediaType.APPLICATION_JSON));
                wrapper.setLastHeartbeatMs(now);
            } catch (IOException e) {
                log.debug("Failed to send heartbeat to connection {}, removing", connectionId, e);
                staleKeys.add(connectionId);
            }
        });
        staleKeys.forEach(this::handleSendFailure);
        if (!staleKeys.isEmpty() || log.isDebugEnabled()) {
            log.info(
                    "SSE heartbeat sweep: removed {} stale emitters, {} active",
                    staleKeys.size(),
                    activeEmitters.size());
        }
    }

    @Getter
    public static class SseEmitterWrapper {

        private final String connectionId;
        private final SseEmitter emitter;
        private final Set<UUID> runIds;
        private final Set<UUID> testSuiteIds;
        private final Set<String> statuses;

        /** Null means "use {@code test-suite-run.sse.cleanup-interval-ms}". */
        private final Long heartbeatIntervalMs;

        /** Invoked once, from the emitter's {@code onError} handler, on disconnect; may be null. */
        private final Runnable onDisconnect;

        private volatile long lastHeartbeatMs;

        public SseEmitterWrapper(
                String connectionId,
                SseEmitter emitter,
                Set<UUID> runIds,
                Set<UUID> testSuiteIds,
                Set<String> statuses,
                Long heartbeatIntervalMs,
                Runnable onDisconnect,
                long createdAtMs) {
            this.connectionId = connectionId;
            this.emitter = emitter;
            this.runIds = runIds;
            this.testSuiteIds = testSuiteIds;
            this.statuses = statuses;
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            this.onDisconnect = onDisconnect;
            this.lastHeartbeatMs = createdAtMs;
        }

        public void setLastHeartbeatMs(long lastHeartbeatMs) {
            this.lastHeartbeatMs = lastHeartbeatMs;
        }

        public boolean matches(TestSuiteRun run) {
            if (runIds != null && !runIds.isEmpty() && !runIds.contains(run.getId())) {
                return false;
            }
            if (testSuiteIds != null && !testSuiteIds.isEmpty() && !testSuiteIds.contains(run.getTestSuiteId())) {
                return false;
            }
            if (statuses != null && !statuses.isEmpty() && !statuses.contains(run.getStatus())) {
                return false;
            }
            return true;
        }
    }
}
