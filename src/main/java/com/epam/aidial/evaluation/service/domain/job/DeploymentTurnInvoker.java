package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * Non-streaming per-turn deployment invoker with retry/backoff for the multi-turn conversation executor.
 * Owns the retry loop (exponential backoff, cancellation checks), a single non-streaming DIAL Core call
 * (rejecting streaming responses and oversize bodies), HTTP-status → {@link ExecutionStatus} mapping, and
 * timeout detection. Returns a {@link TurnOutcome} carrying the final status, HTTP status code, raw
 * response body, and the number of retries performed for the turn.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class DeploymentTurnInvoker {

    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final QuietJsonService jsonService;

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

        TurnOutcome last = null;
        int retries = 0;
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
                retries++;
            }

            last = invokeSingle(context, method, path, headers, queryParams, body);
            if (!DeploymentInvocationSupport.isRetryable(last.status(), last.statusCode(), attempt, maxRetries)) {
                break;
            }
        }
        return new TurnOutcome(last.status(), last.statusCode(), last.responseBody(), retries);
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

            // Multi-turn is non-streaming only: a streaming response cannot be consumed here.
            if (result.streaming()) {
                return new TurnOutcome(ExecutionStatus.ERROR, statusCode, null, 0);
            }

            String responseBody = jsonService.writeOrToString(result.body());
            if (responseBody != null
                    && responseBody.getBytes(StandardCharsets.UTF_8).length > context.getMaxResponseSizeBytes()) {
                status = ExecutionStatus.ERROR;
            }
            return new TurnOutcome(status, statusCode, responseBody, 0);
        } catch (Exception e) {
            final ExecutionStatus status =
                    DeploymentInvocationSupport.isTimeoutException(e) ? ExecutionStatus.TIMEOUT : ExecutionStatus.ERROR;
            return new TurnOutcome(status, null, null, 0);
        }
    }
}
