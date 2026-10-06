package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.configuration.properties.dialapp.DialAppProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fires eval execution by calling DIAL Core's Application Route and consuming the SSE stream
 * to maintain PRK liveness. Runs on a virtual thread to avoid blocking the request thread.
 *
 * <p>The trigger call uses the user's JWT exactly once to request eval execution from DIAL Core.
 * DIAL Core validates the JWT, generates a per-request key (PRK), and proxies the request to EF's
 * internal endpoint (`POST /api/internal/runs/{runId}/execute`). This component consumes the SSE
 * stream returned by that endpoint, keeping the HTTP connection open for the duration of eval
 * execution, which keeps the PRK alive.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dial-app-proxy.enabled", havingValue = "true")
public class DialRouteTriggerClient {

    private final DialAppProperties dialAppProperties;
    private final RestClient dialRouteTriggerRestClient;

    /**
     * Triggers eval execution via DIAL Core's Application Route.
     *
     * @param runId the run id to trigger
     * @param jwtCredential the user's JWT (BEARER-kind credential); used once for DIAL Core
     *     validation
     */
    public void triggerEvalRun(UUID runId, CallerCredential jwtCredential) {
        String deploymentName = dialAppProperties.getDeploymentName();
        String routeUrl = String.format("/v1/deployments/%s/route/api/internal/runs/%s/execute", deploymentName, runId);

        try {
            var response = dialRouteTriggerRestClient
                    .post()
                    .uri(routeUrl)
                    .header(jwtCredential.headerName(), jwtCredential.headerValue())
                    .retrieve()
                    .toEntity(InputStream.class);

            if (response != null && response.getBody() != null) {
                consumeStream(runId, response.getBody());
            }
        } catch (Exception e) {
            log.warn("DIAL Core route trigger failed for run {}: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Consumes the SSE stream from the internal eval endpoint (proxied through DIAL Core) until
     * the stream closes. This keeps the HTTP connection to DIAL Core alive, which maintains the
     * PRK's validity.
     *
     * <p><b>Timeout behavior:</b> The HTTP-level read timeout ({@code triggerReadTimeoutMs}) only
     * triggers if the socket is silent (no data received) for that duration. As long as the eval
     * is running and heartbeat events flow through, the timeout does NOT fire. The stream closes
     * naturally when the eval reaches a terminal state (completed/failed/cancelled) and the
     * internal endpoint closes the SSE emitter. The timeout is a safety net for DIAL Core hanging
     * indefinitely without closing the connection.
     */
    private void consumeStream(UUID runId, InputStream stream) {
        if (stream == null) {
            return;
        }

        try (stream;
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            long lineCount = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (log.isTraceEnabled()) {
                    log.trace("DIAL Core route stream event for run {}: line {}", runId, lineCount);
                }
            }
            log.info("DIAL Core route stream closed for run {} after consuming {} events", runId, lineCount);
        } catch (IOException e) {
            log.warn("IOException while consuming DIAL Core route stream for run {}: {}", runId, e.getMessage(), e);
        }
    }
}
