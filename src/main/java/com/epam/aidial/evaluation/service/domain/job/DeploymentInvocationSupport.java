package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateless helpers shared by the deployment-invocation paths ({@code EvaluationWorker},
 * {@code DeploymentTurnInvoker}): HTTP-status → {@link ExecutionStatus} mapping, timeout detection,
 * exponential backoff, the retry predicate, and query-param assembly. Pure functions with no dependencies,
 * so they live here rather than being duplicated per caller.
 */
public final class DeploymentInvocationSupport {

    private DeploymentInvocationSupport() {}

    /** Error code used when a call was never issued because its rate-limit token wait was interrupted. */
    public static final String CANCELLED_ERROR_CODE = "CANCELLED";

    private static final String CANCELLED_MESSAGE = "Interrupted while waiting for a rate limit token";

    /**
     * The error envelope for a call that was never issued because run cancellation interrupted its
     * rate-limit token wait. Shared so every gate call site reports the interruption identically — the
     * single-request path builds the same {@code {"error":{"code","message"}}} shape, and a turn/chain row
     * that ever reaches the database must be as diagnosable as that one.
     */
    public static String cancelledEnvelope(QuietJsonService jsonService) {
        return errorEnvelope(CANCELLED_ERROR_CODE, CANCELLED_MESSAGE, jsonService);
    }

    /**
     * The {@code {"error":{"code","message"}}} envelope stored in {@code response_body} for a row whose call
     * produced no response of its own. The single builder for that shape: the single-request path, the chain
     * step's unexpected-failure path, and the cancellation envelope above all route through here, so a
     * consumer parsing {@code response_body} sees one structure regardless of which executor wrote the row.
     * A null {@code message} is normalized to {@code "Unknown error"} rather than a JSON null, so the field is
     * always a string.
     */
    public static String errorEnvelope(String code, String message, QuietJsonService jsonService) {
        final ObjectNode error = jsonService.createObjectNode();
        error.put("code", code);
        error.put("message", message != null ? message : "Unknown error");
        final ObjectNode root = jsonService.createObjectNode();
        root.set("error", error);
        return jsonService.writeOrToString(root);
    }

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

    /**
     * Truncates {@code value} to at most {@code maxBytes} UTF-8 bytes, cutting on a byte boundary (a split
     * multi-byte char is dropped/replaced by the decoder). Returns the input unchanged when it is null or
     * already within the limit. Callers that persist the result should JSON-escape it (as both the single-
     * and multi-turn paths do) so the stored {@code response_body} stays valid JSON.
     */
    public static String truncateUtf8(String value, long maxBytes) {
        if (value == null) {
            return null;
        }
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        return new String(bytes, 0, (int) maxBytes, StandardCharsets.UTF_8);
    }

    /**
     * Builds request headers from resolved key/value pairs, dropping any whose name is blacklisted
     * (case-insensitively) and any with a null key or value. Shared by the multi-turn turn loop and the
     * multi-request chain step, which previously carried byte-identical copies.
     *
     * <p>{@code EvaluationWorker} keeps its own variant deliberately: it additionally logs each skipped
     * header, which the per-turn and per-chain-request paths would emit once per turn/request.
     */
    public static HttpHeaders buildHeaders(
            List<KeyValueTemplateDto> resolvedHeaders, Collection<String> headerBlacklist) {
        final HttpHeaders headers = new HttpHeaders();
        final Set<String> blacklist = headerBlacklist == null
                ? Set.of()
                : headerBlacklist.stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (resolvedHeaders != null) {
            for (KeyValueTemplateDto kv : resolvedHeaders) {
                if (kv.getKey() != null
                        && kv.getValue() != null
                        && !blacklist.contains(kv.getKey().toLowerCase())) {
                    headers.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return headers;
    }

    /**
     * Serializes a resolved body for the {@code request_body} analytics column: a JSON body stores just its
     * content map (no {@code contentType} wrapper), anything else stores the body object itself. Shared so
     * the chain path and the single-request path cannot drift in what they persist.
     */
    public static String serializeBodyForAnalytics(ResolvedBodyDto body, QuietJsonService jsonService) {
        if (body == null) {
            return null;
        }
        final Object toSerialize = body instanceof ResolvedJsonBodyDto jsonBody ? jsonBody.getContent() : body;
        return jsonService.writeOrToString(toSerialize);
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
