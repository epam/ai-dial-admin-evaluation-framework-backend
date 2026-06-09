package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Invokes DIAL Core deployment endpoints (POST/GET/etc.) with a dedicated RestClient
 * configured for longer read timeouts (LLM inference). No retry logic.
 *
 * <p>Path encoding contract: callers may pass the {@code path} either raw (with literal
 * special characters) or pre-encoded (with {@code %XX} escapes — the shape that DIAL Core's
 * {@code GET /v1/deployments} surfaces in {@code id} fields). The invoker normalizes both
 * shapes to a single-encoded wire path so DIAL Core's {@code UrlUtil.decodePath} (called
 * exactly once on the server side) recovers the canonical resource URL. This mirrors the
 * structural decode-once / per-segment-encode pattern used by
 * {@link com.epam.aidial.evaluation.client.mcp.McpToolInvoker}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class DialCoreDeploymentInvoker {

    private static final Set<HttpMethod> BODY_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    @Qualifier("dialCoreTryOutRestClient")
    private final RestClient dialCoreTryOutRestClient;

    private final ObjectMapper objectMapper;
    private final DialCoreProperties dialCoreProperties;

    /**
     * Invokes a DIAL Core deployment endpoint (non-streaming, reads full response).
     */
    public DeploymentInvocationResponse invoke(
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {
        boolean includeBody = BODY_METHODS.contains(method) && body != null;
        try {
            RestClient.RequestBodySpec spec = dialCoreTryOutRestClient
                    .method(method)
                    .uri(buildEncodedUri(path, queryParams))
                    .headers(httpHeaders -> {
                        if (headers != null) {
                            httpHeaders.addAll(headers);
                        }
                    });
            if (includeBody) {
                spec.body(body);
            }
            return spec.exchange((request, response) -> {
                int statusCode = response.getStatusCode().value();
                String rawBody = new String(response.getBody().readAllBytes());
                Object parsedBody = parseBody(rawBody);
                return new DeploymentInvocationResponse(statusCode, parsedBody);
            });
        } catch (ResourceAccessException ex) {
            throw mapResourceAccessException(ex);
        }
    }

    /**
     * Invokes a DIAL Core deployment endpoint with streaming support.
     * Auto-detects streaming from response Content-Type.
     * Returns a DeploymentInvocationResult that must be closed (try-with-resources).
     *
     * @param method      HTTP method
     * @param path        full path
     * @param headers     custom headers
     * @param queryParams query parameters
     * @param body        request body
     * @return result with streaming detection and raw InputStream access
     */
    public DeploymentInvocationResult invokeWithStreaming(
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {
        boolean includeBody = BODY_METHODS.contains(method) && body != null;
        try {
            RestClient.RequestBodySpec spec = dialCoreTryOutRestClient
                    .method(method)
                    .uri(buildEncodedUri(path, queryParams))
                    .headers(httpHeaders -> {
                        if (headers != null) {
                            httpHeaders.addAll(headers);
                        }
                    });
            if (includeBody) {
                spec.body(body);
            }
            // close=false: caller owns the response lifecycle via DeploymentInvocationResult.close()
            return spec.exchange((request, response) -> handleStreamingResponse(response), false);
        } catch (ResourceAccessException ex) {
            throw mapResourceAccessException(ex);
        }
    }

    /**
     * Composes the absolute request URI with single-encoded path and query components.
     *
     * <p>The path is normalized by splitting on {@code /}, decoding each non-empty segment
     * (idempotent against already-raw segments), and re-encoding once per-segment via
     * {@link UriComponentsBuilder#pathSegment}. The configured DIAL Core base URL is then
     * prepended, query parameters are appended raw, and {@code build().encode()} performs a
     * single, final encoding pass over the entire {@link UriComponents}. The resulting
     * absolute URI is handed to {@link RestClient#uri(URI)} so it is sent on the wire
     * verbatim — bypassing the {@code RestClient}'s configured {@link
     * org.springframework.web.util.UriBuilderFactory}, which under {@code TEMPLATE_AND_VALUES}
     * mode would otherwise double-encode pre-encoded path bytes.
     */
    private URI buildEncodedUri(String path, MultiValueMap<String, String> queryParams) {
        String[] decodedSegments = decodePathSegments(path);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(dialCoreProperties.getBaseUrl())
                .pathSegment(decodedSegments);
        if (queryParams != null && !queryParams.isEmpty()) {
            builder.queryParams(queryParams);
        }
        return builder.build().encode().toUri();
    }

    private static String[] decodePathSegments(String path) {
        if (path == null || path.isEmpty()) {
            return new String[0];
        }
        String[] rawSegments = path.split("/");
        List<String> decoded = new ArrayList<>(rawSegments.length);
        for (String segment : rawSegments) {
            if (!segment.isEmpty()) {
                decoded.add(UriUtils.decode(segment, StandardCharsets.UTF_8));
            }
        }
        return decoded.toArray(new String[0]);
    }

    private DeploymentInvocationResult handleStreamingResponse(ClientHttpResponse response) throws java.io.IOException {
        try {
            int statusCode = response.getStatusCode().value();
            HttpHeaders responseHeaders = response.getHeaders();
            MediaType contentType = responseHeaders.getContentType();

            boolean streaming = contentType != null
                    && "text".equals(contentType.getType())
                    && "event-stream".equals(contentType.getSubtype());

            if (streaming) {
                InputStream eventStream = response.getBody();
                return new DeploymentInvocationResult(statusCode, true, null, eventStream, responseHeaders, response);
            } else {
                String rawBody = new String(response.getBody().readAllBytes());
                Object parsedBody = parseBody(rawBody);
                return new DeploymentInvocationResult(statusCode, false, parsedBody, null, responseHeaders, response);
            }
        } catch (IOException e) {
            response.close();
            throw e;
        }
    }

    private Object parseBody(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, Object.class);
        } catch (JacksonException ex) {
            return rawBody;
        }
    }

    private static DialCoreClientException mapResourceAccessException(ResourceAccessException ex) {
        Throwable cause = getRootCause(ex);
        if (cause instanceof SocketTimeoutException) {
            return new DialCoreClientException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "DIAL Core deployment did not respond within the configured timeout",
                    ex);
        }
        return new DialCoreClientException(
                HttpStatus.BAD_GATEWAY, "Failed to connect to DIAL Core deployment: " + cause.getMessage(), ex);
    }

    private static Throwable getRootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
