package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import java.util.List;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Stateless helpers shared by the deployment-invocation paths ({@code EvaluationWorker},
 * {@code DeploymentTurnInvoker}, and the metric retry loop): HTTP-status → {@link ExecutionStatus} mapping,
 * timeout detection, exponential backoff, the retry predicate, and query-param assembly. Pure functions with
 * no dependencies, so they live here rather than being duplicated per caller.
 */
public final class DeploymentInvocationSupport {

    private DeploymentInvocationSupport() {}

    /** Maps an HTTP status code to an execution status: 2xx → SUCCESS, 401/403 → ERROR, everything else → FAILED. */
    public static ExecutionStatus resolveExecutionStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return ExecutionStatus.SUCCESS;
        }
        if (statusCode == 401 || statusCode == 403) {
            return ExecutionStatus.ERROR;
        }
        return ExecutionStatus.FAILED;
    }

    /** True when a timeout appears anywhere in the throwable's cause chain (by simple class-name match). */
    public static boolean isTimeoutException(Throwable e) {
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

    /**
     * Exponential backoff delay for a given (1-based) retry attempt: {@code baseDelayMs * multiplier^(attempt-1)},
     * capped at {@code maxDelayMs}.
     */
    public static long nextBackoffDelayMs(int attempt, long baseDelayMs, double multiplier, long maxDelayMs) {
        final long delay = (long) (baseDelayMs * Math.pow(multiplier, attempt - 1));
        return Math.min(delay, maxDelayMs);
    }

    /**
     * Whether a completed attempt should be retried: retryable while attempts remain and the outcome is a
     * TIMEOUT, a network error (ERROR with no status code), HTTP 429, or any 5xx. 401/403 and success are not.
     */
    public static boolean isRetryable(ExecutionStatus status, Integer statusCode, int attempt, int maxRetries) {
        if (attempt >= maxRetries) {
            return false;
        }
        if (status == ExecutionStatus.TIMEOUT) {
            return true;
        }
        if (status == ExecutionStatus.ERROR && statusCode == null) {
            return true;
        }
        return statusCode != null && (statusCode == 429 || statusCode >= 500);
    }

    /** Builds a query-param multi-value map from resolved key/value pairs, skipping any with a null key or value. */
    public static MultiValueMap<String, String> buildQueryParams(List<KeyValueTemplateDto> resolvedParams) {
        final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        if (resolvedParams != null) {
            for (KeyValueTemplateDto kv : resolvedParams) {
                if (kv.getKey() != null && kv.getValue() != null) {
                    queryParams.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return queryParams;
    }
}
