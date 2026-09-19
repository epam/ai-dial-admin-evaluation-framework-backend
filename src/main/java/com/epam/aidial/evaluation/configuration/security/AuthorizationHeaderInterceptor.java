package com.epam.aidial.evaluation.configuration.security;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Captures the caller's credential (bearer token or API key) from the inbound request into
 * {@link AuthorizationTokenHolder}, mirroring {@code ApiKeyAuthenticationFilter}'s precedence: a
 * non-blank {@code Authorization} header always wins, and the {@code Api-Key} header is only
 * captured when {@code Authorization} is blank.
 */
@LogExecution
public class AuthorizationHeaderInterceptor implements AsyncHandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(
            @NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String apiKeyHeader = request.getHeader(CallerCredential.API_KEY_HEADER);
        if (authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
            AuthorizationTokenHolder.setCredential(
                    CallerCredential.bearer(authorizationHeader.substring(BEARER_PREFIX.length())));
        } else if (StringUtils.isBlank(authorizationHeader) && StringUtils.isNotBlank(apiKeyHeader)) {
            AuthorizationTokenHolder.setCredential(CallerCredential.apiKey(apiKeyHeader));
        } else {
            AuthorizationTokenHolder.clearToken();
        }
        return true;
    }

    @Override
    public void afterCompletion(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull Object handler,
            Exception ex) {
        AuthorizationTokenHolder.clearToken();
    }

    @Override
    public void afterConcurrentHandlingStarted(
            @NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {
        AuthorizationTokenHolder.clearToken();
    }
}
