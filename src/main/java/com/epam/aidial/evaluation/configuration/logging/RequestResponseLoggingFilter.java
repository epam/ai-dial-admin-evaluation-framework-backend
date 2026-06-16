package com.epam.aidial.evaluation.configuration.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(value = "logging.request-response.enabled", havingValue = "true")
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_PAYLOAD_LENGTH = 10000;

    @Override
    protected void doFilterInternal(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull FilterChain filterChain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 0);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            logRequest(requestWrapper);
            logResponse(responseWrapper, duration);
            responseWrapper.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        if (!log.isDebugEnabled()) {
            return;
        }

        String requestBody = getRequestBody(request);
        log.debug(
                "Incoming request: {} {} | Query: {} | Body: {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                requestBody);
    }

    private void logResponse(ContentCachingResponseWrapper response, long duration) {
        if (!log.isDebugEnabled()) {
            return;
        }

        String responseBody = getResponseBody(response);
        log.debug(
                "Outgoing response: Status {} | Duration: {}ms | Body: {}",
                response.getStatus(),
                duration,
                responseBody);
    }

    private String getRequestBody(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) {
            return "[empty]";
        }
        return truncateBody(new String(content, StandardCharsets.UTF_8));
    }

    private String getResponseBody(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        if (content.length == 0) {
            return "[empty]";
        }
        return truncateBody(new String(content, StandardCharsets.UTF_8));
    }

    private String truncateBody(String body) {
        if (body.length() > MAX_PAYLOAD_LENGTH) {
            return body.substring(0, MAX_PAYLOAD_LENGTH) + "... [truncated]";
        }
        return body;
    }

    @Override
    protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/swagger") || path.startsWith("/v3/api-docs");
    }
}
