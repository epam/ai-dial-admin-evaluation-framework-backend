package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-turn deployment invoker with retry/backoff for the multi-turn test case executor. Owns the retry loop
 * (exponential backoff, cancellation checks, retry-attempt logging), a single DIAL Core call — streaming
 * (SSE, assembled via {@link StreamingResponseAccumulator}) or non-streaming, both subject to the same
 * oversize-body handling — HTTP-status → {@link ExecutionStatus} mapping, and timeout detection. Returns a
 * {@link TurnOutcome} carrying the final status, HTTP status code, raw response body, retry count, and
 * {@code logDetails} JSON for the turn. Mirrors {@code EvaluationWorker}'s single-turn invocation path
 * ({@code invokeWithRetries}/{@code invokeSingle}) so a later unification of the two loops loses no behavior.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class DeploymentTurnInvoker {

    private static final String INVOCATION_ERROR_CODE = "INVOCATION_ERROR";

    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final QuietJsonService jsonService;
    private final SseEventParser sseEventParser;
    private final SseEventProcessingProperties sseEventProcessingProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TurnOutcome invoke(
            EvaluationContext context,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {

        final int maxRetries = context.getMaxRetries();
        final long retryDelayMs = context.getRetryDelayMs();
        final double multiplier = context.getRetryBackoffMultiplier();
        final long maxRetryDelay = context.getMaxRetryDelayMs();

        final List<DeploymentInvocationSupport.RetryAttemptLog> retryAttempts = new ArrayList<>();
        TurnOutcome last = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                if (context.getCancellationSignal().get()) {
                    break;
                }
                final long delay = DeploymentInvocationSupport.nextBackoffDelayMs(
                        attempt, retryDelayMs, multiplier, maxRetryDelay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (context.getCancellationSignal().get()) {
                    break;
                }
            }

            final long attemptStartMs = clock.millis();
            last = invokeSingle(context, method, path, headers, queryParams, body);
            if (!DeploymentInvocationSupport.isRetryable(last.status(), last.statusCode(), attempt, maxRetries)) {
                break;
            }

            final long attemptDurationMs = clock.millis() - attemptStartMs;
            final String errorType = DeploymentInvocationSupport.resolveErrorType(last.status());
            retryAttempts.add(new DeploymentInvocationSupport.RetryAttemptLog(
                    attempt + 1, last.statusCode(), errorType, attemptDurationMs));
        }

        final int retryCount = retryAttempts.size();
        final String logDetails = retryCount > 0
                ? DeploymentInvocationSupport.buildRetryLogDetailsJson(retryAttempts, objectMapper)
                : null;
        return new TurnOutcome(last.status(), last.statusCode(), last.responseBody(), retryCount, logDetails);
    }

    private TurnOutcome invokeSingle(
            EvaluationContext context,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {
        try (DeploymentInvocationResult result =
                deploymentInvoker.invokeWithStreaming(method, path, headers, queryParams, body)) {
            final int statusCode = result.statusCode();
            ExecutionStatus status = DeploymentInvocationSupport.resolveExecutionStatus(statusCode);

            String responseBody;
            if (result.streaming()) {
                // Streaming response: idle timeout = per-run request timeout; absolute cap = global property.
                // Mirrors EvaluationWorker.invokeSingle's streaming branch.
                final StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                        sseEventParser,
                        objectMapper,
                        context.getRequestTimeoutMs(),
                        sseEventProcessingProperties.getMaxTotalDurationMs(),
                        context.getMaxResponseSizeBytes());
                accumulator.accumulate(result.eventStream());

                responseBody = accumulator.getResponseBody();
                if (accumulator.getExecutionStatus() != ExecutionStatus.SUCCESS) {
                    status = accumulator.getExecutionStatus();
                }
            } else {
                responseBody = jsonService.writeOrToString(result.body());
                if (responseBody != null
                        && responseBody.getBytes(StandardCharsets.UTF_8).length > context.getMaxResponseSizeBytes()) {
                    status = ExecutionStatus.ERROR;
                    // Mirror the single-turn path: cap the persisted body so an oversize turn never writes an
                    // unbounded blob to test_case_run_results.response_body. writeOrToString re-escapes the
                    // truncated fragment so the stored value stays valid JSON.
                    responseBody = jsonService.writeOrToString(
                            DeploymentInvocationSupport.truncateUtf8(responseBody, context.getMaxResponseSizeBytes()));
                }
            }
            return new TurnOutcome(status, statusCode, responseBody, 0, null);
        } catch (Exception e) {
            final ExecutionStatus status =
                    DeploymentInvocationSupport.isTimeoutException(e) ? ExecutionStatus.TIMEOUT : ExecutionStatus.ERROR;
            log.warn("Turn invocation failed ({}): {}", status, e.getMessage(), e);
            final String errorBody =
                    DeploymentInvocationSupport.buildErrorEnvelope(INVOCATION_ERROR_CODE, e.getMessage(), objectMapper);
            return new TurnOutcome(status, null, errorBody, 0, null);
        }
    }
}
