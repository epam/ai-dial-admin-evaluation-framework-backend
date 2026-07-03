package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-streaming per-turn deployment invoker with retry/backoff for the multi-step conversation executor.
 * Owns the retry loop (exponential backoff, cancellation checks), a single non-streaming DIAL Core call
 * (rejecting streaming responses and oversize bodies), HTTP-status → {@link ExecutionStatus} mapping, and
 * timeout detection. Returns a {@link StepOutcome} carrying the final status, HTTP status code, raw
 * response body, and the number of retries performed for the turn.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class DeploymentTurnInvoker {

    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final ObjectMapper objectMapper;

    public StepOutcome invoke(
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

        StepOutcome last = null;
        int retries = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                if (context.getCancellationSignal().get()) {
                    break;
                }
                final long delay = Math.min((long) (retryDelayMs * Math.pow(multiplier, attempt - 1)), maxRetryDelay);
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
            if (!shouldRetry(last, attempt, maxRetries)) {
                break;
            }
        }
        return new StepOutcome(last.status(), last.statusCode(), last.responseBody(), retries);
    }

    private StepOutcome invokeSingle(
            EvaluationContext context,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {
        try (DeploymentInvocationResult result =
                deploymentInvoker.invokeWithStreaming(method, path, headers, queryParams, body)) {
            final int statusCode = result.statusCode();
            ExecutionStatus status = resolveExecutionStatus(statusCode);

            // Multi-step is non-streaming only: a streaming response cannot be consumed here.
            if (result.streaming()) {
                return new StepOutcome(ExecutionStatus.ERROR, statusCode, null, 0);
            }

            String responseBody = serialize(result.body());
            if (responseBody != null
                    && responseBody.getBytes(StandardCharsets.UTF_8).length > context.getMaxResponseSizeBytes()) {
                status = ExecutionStatus.ERROR;
            }
            return new StepOutcome(status, statusCode, responseBody, 0);
        } catch (Exception e) {
            final ExecutionStatus status = isTimeoutException(e) ? ExecutionStatus.TIMEOUT : ExecutionStatus.ERROR;
            return new StepOutcome(status, null, null, 0);
        }
    }

    private boolean shouldRetry(StepOutcome outcome, int attempt, int maxRetries) {
        if (attempt >= maxRetries) {
            return false;
        }
        final ExecutionStatus status = outcome.status();
        final Integer statusCode = outcome.statusCode();
        if (status == ExecutionStatus.TIMEOUT) {
            return true;
        }
        if (status == ExecutionStatus.ERROR && statusCode == null) {
            return true;
        }
        return statusCode != null && (statusCode == 429 || statusCode >= 500);
    }

    private ExecutionStatus resolveExecutionStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return ExecutionStatus.SUCCESS;
        }
        if (statusCode == 401 || statusCode == 403) {
            return ExecutionStatus.ERROR;
        }
        return ExecutionStatus.FAILED;
    }

    private boolean isTimeoutException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            final String name = cause.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            return body.toString();
        }
    }
}
