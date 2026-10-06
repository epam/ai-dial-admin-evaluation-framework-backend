package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.properties.dialapp.DialAppProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.job.ActiveRunRegistry;
import com.epam.aidial.evaluation.service.domain.job.RunAlreadyActiveException;
import com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Internal endpoint for DIAL App-triggered eval execution. When `dial-app-proxy.enabled=true`,
 * DIAL Core's Application Route proxies requests to this endpoint with a per-request key (PRK)
 * in the `Api-Key` header. The endpoint dispatches eval execution and streams progress/status
 * events back via SSE, maintaining the connection open to keep the PRK alive for the run's
 * duration.
 *
 * <p>Authenticated by the existing Spring Security chain (API-Key auth); no special
 * `permitAll` handling needed.
 */
@RestController
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dial-app-proxy.enabled", havingValue = "true")
@RequestMapping("/api/internal")
public class EvalExecuteInternalController {

    private final TestSuiteRunService testSuiteRunService;
    private final TestSuiteEvaluationJob testSuiteEvaluationJob;
    private final TestSuiteRunSseService sseService;
    private final ActiveRunRegistry activeRunRegistry;
    private final DialAppProperties dialAppProperties;

    /**
     * Triggers eval execution for an existing run via a DIAL Application Route. The run must
     * exist; the caller must already be authenticated by the existing API-Key auth chain
     * (DIAL Core per-request key).
     *
     * <p>The SSE emitter is created and registered with {@link TestSuiteRunSseService} BEFORE
     * {@link TestSuiteEvaluationJob#dispatch} is called, not after. The job is dispatched onto a
     * separate executor and can terminate (and fire its terminal {@code notifyStatusUpdate}) within
     * microseconds of a fast-failing snapshot phase; creating the emitter only after {@code dispatch}
     * returns would leave a window where that terminal notification is sent to no registered emitter,
     * silently stranding the SSE stream open until its 30-minute timeout. Registering the emitter
     * first guarantees it is present for every notification the job can possibly send.
     *
     * <p>Dispatch (and its own {@code ActiveRunRegistry.registerIfAbsent} check) is delegated
     * entirely to {@link TestSuiteEvaluationJob#dispatch}; this method does not pre-register a
     * handle itself, since that would always win the race and make {@code dispatch} throw
     * {@link RunAlreadyActiveException} on every call. That exception (or an {@link Error} from
     * executor submission failure, per {@code dispatch}'s own contract) is mapped to HTTP 409 by
     * {@code DefaultExceptionHandler} once it reaches there — but since the emitter was already
     * created by this point, it is explicitly discarded from {@link TestSuiteRunSseService}'s
     * tracking first, so no orphaned emitter is left registered for a run that was never actually
     * dispatched.
     *
     * @param runId the ID of the run to execute
     * @return HTTP 200 with SSE stream on success; HTTP 404 if run not found; HTTP 409 if run
     *     is already active; HTTP 401 if not authenticated
     */
    @PostMapping(value = "/runs/{runId}/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> executeRun(@PathVariable UUID runId) {
        // Load the run via service layer (404 if not found) — EntityNotFoundException is thrown by
        // getRun
        var runDto = testSuiteRunService.getRun(runId);

        // Get the caller's credential (already an API_KEY-kind PRK from AuthorizationTokenHolder)
        var credential = AuthorizationTokenHolder.getCredential();
        if (credential == null) {
            throw new IllegalStateException("Expected an authenticated API-Key credential in AuthorizationTokenHolder");
        }

        // Create an SSE emitter with a configurable heartbeat specific to this internal endpoint, and
        // cancel the run if DIAL Core (or the eval client) disconnects mid-run. Registered BEFORE
        // dispatch so the async job's terminal notification can never fire before the emitter exists.
        SseEmitter emitter = sseService.createEmitterWithHeartbeat(
                Collections.singleton(runId),
                Collections.singleton(runDto.getTestSuiteId()),
                null, // no status filter
                dialAppProperties.getHeartbeatIntervalMs(),
                () -> activeRunRegistry.cancel(runId));

        try {
            // Dispatch the evaluation job with skipDeploymentPhase=false. Throws RunAlreadyActiveException
            // (mapped to HTTP 409 by DefaultExceptionHandler) if a handle is already registered for runId.
            testSuiteEvaluationJob.dispatch(runId, credential, false);
        } catch (RuntimeException | Error e) {
            // Dispatch failed before the job ever ran: Spring MVC never wired this emitter to a servlet
            // async context (it is only attached once returned as the response body below), so
            // emitter.complete() would not fire its onCompletion callback. Discard it from
            // TestSuiteRunSseService's tracking directly instead, so it is never left registered forever.
            sseService.discardEmitter(emitter);
            throw e;
        }

        return ResponseEntity.ok().body(emitter);
    }
}
