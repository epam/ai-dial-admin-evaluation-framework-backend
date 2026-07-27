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
 * Non-streaming per-turn deployment invoker with retry/backoff for the multi-turn test case executor.
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
        return new TurnOutcome(last.status(), last.statusCode(), last.responseBody(), retries, last.issued());
    }

    private TurnOutcome invokeSingle(
            EvaluationContext context,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {
        // One token per HTTP attempt, retries included: a retry is a real request, and retries cluster
        // precisely when the target is already returning 429/5xx. An interrupted wait skips the call so run
        // cancellation is not delayed; the executor drops the row because the interrupt flag is set.
        //
        // The CANCELLED envelope is carried in responseBody deliberately: the turn/chain row builders do not
        // populate log_details for this case (MultiTurnExecutor writes a null, ChainExecutor fills it only
        // for unresolved response fields), so responseBody is the only field that can distinguish a
        // rate-limit cancellation from any other ERROR if the row ever does get persisted.
        if (!context.getRateLimiter().tryAcquire()) {
            return TurnOutcome.notIssued(DeploymentInvocationSupport.cancelledEnvelope(jsonService));
        }

        try (DeploymentInvocationResult result =
                deploymentInvoker.invokeWithStreaming(method, path, headers, queryParams, body)) {
            final int statusCode = result.statusCode();
            ExecutionStatus status = DeploymentInvocationSupport.resolveExecutionStatus(statusCode);

            if (result.streaming()) {
                return TurnOutcome.issued(ExecutionStatus.ERROR, statusCode, null, 0);
            }

            String responseBody = jsonService.writeOrToString(result.body());
            if (responseBody != null
                    && responseBody.getBytes(StandardCharsets.UTF_8).length > context.getMaxResponseSizeBytes()) {
                status = ExecutionStatus.ERROR;
                // Mirror the single-turn path: cap the persisted body so an oversize turn never writes an
                // unbounded blob to test_case_run_results.response_body. writeOrToString re-escapes the
                // truncated fragment so the stored value stays valid JSON.
                responseBody = jsonService.writeOrToString(
                        DeploymentInvocationSupport.truncateUtf8(responseBody, context.getMaxResponseSizeBytes()));
            }
            return TurnOutcome.issued(status, statusCode, responseBody, 0);
        } catch (Exception e) {
            final ExecutionStatus status =
                    DeploymentInvocationSupport.isTimeoutException(e) ? ExecutionStatus.TIMEOUT : ExecutionStatus.ERROR;
            log.warn("Turn invocation failed ({}): {}", status, e.getMessage(), e);
            return TurnOutcome.issued(status, null, null, 0);
        }
    }
}
