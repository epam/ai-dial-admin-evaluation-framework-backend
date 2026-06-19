package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.dto.SseStatusEventDto;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private final TestSuiteRunProperties properties;

    private final ConcurrentHashMap<String, SseEmitterWrapper> activeEmitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(Set<UUID> runIds, Set<UUID> testSuiteIds, Set<String> statuses) {
        long timeoutMs = properties.getSse().getTimeoutMinutes() * 60L * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        String connectionId = UUID.randomUUID().toString();

        SseEmitterWrapper wrapper = new SseEmitterWrapper(connectionId, emitter, runIds, testSuiteIds, statuses);
        activeEmitters.put(connectionId, wrapper);

        emitter.onCompletion(() -> activeEmitters.remove(connectionId));
        emitter.onTimeout(() -> activeEmitters.remove(connectionId));
        emitter.onError(_ -> activeEmitters.remove(connectionId));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("connectionId", connectionId)));
        } catch (IOException e) {
            log.debug("Failed to send initial SSE connected event for connection {}", connectionId, e);
        }

        return emitter;
    }

    public void notifyStatusUpdate(TestSuiteRun run) {
        SseStatusEventDto event = SseStatusEventDto.builder()
                .runId(run.getId())
                .testSuiteId(run.getTestSuiteId())
                .status(run.getStatus())
                .message("Status changed to " + run.getStatus())
                .timestamp(System.currentTimeMillis())
                .build();

        activeEmitters.values().forEach(wrapper -> {
            if (wrapper.matches(run)) {
                try {
                    wrapper.getEmitter()
                            .send(SseEmitter.event().name("status-update").data(event));
                } catch (IOException e) {
                    log.debug("Failed to send SSE event to connection {}, removing", wrapper.getConnectionId(), e);
                    activeEmitters.remove(wrapper.getConnectionId());
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
        progressEvent.put("timestamp", System.currentTimeMillis());

        activeEmitters.values().forEach(wrapper -> {
            try {
                wrapper.getEmitter().send(SseEmitter.event().name("progress").data(progressEvent));
            } catch (IOException e) {
                log.debug("Failed to send progress SSE to connection {}", wrapper.getConnectionId(), e);
                activeEmitters.remove(wrapper.getConnectionId());
            }
        });
    }

    @Scheduled(fixedDelayString = "${test-suite-run.sse.cleanup-interval-ms:300000}")
    public void cleanupStaleEmitters() {
        List<String> staleKeys = new ArrayList<>();
        activeEmitters.forEach((connectionId, wrapper) -> {
            try {
                wrapper.getEmitter().send(SseEmitter.event().name("heartbeat").data("ping", MediaType.TEXT_PLAIN));
            } catch (IOException e) {
                staleKeys.add(connectionId);
            }
        });
        staleKeys.forEach(activeEmitters::remove);
        if (!staleKeys.isEmpty() || log.isDebugEnabled()) {
            log.info("SSE cleanup: removed {} stale emitters, {} active", staleKeys.size(), activeEmitters.size());
        }
    }

    public static class SseEmitterWrapper {

        private final String connectionId;
        private final SseEmitter emitter;
        private final Set<UUID> runIds;
        private final Set<UUID> testSuiteIds;
        private final Set<String> statuses;

        public SseEmitterWrapper(
                String connectionId,
                SseEmitter emitter,
                Set<UUID> runIds,
                Set<UUID> testSuiteIds,
                Set<String> statuses) {
            this.connectionId = connectionId;
            this.emitter = emitter;
            this.runIds = runIds;
            this.testSuiteIds = testSuiteIds;
            this.statuses = statuses;
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

        public String getConnectionId() {
            return connectionId;
        }

        public SseEmitter getEmitter() {
            return emitter;
        }
    }
}
