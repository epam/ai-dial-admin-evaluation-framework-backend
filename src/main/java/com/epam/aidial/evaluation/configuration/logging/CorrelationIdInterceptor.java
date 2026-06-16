package com.epam.aidial.evaluation.configuration.logging;

import com.epam.aidial.evaluation.constants.SecurityConstants;
import com.epam.aidial.evaluation.utils.TraceContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
public class CorrelationIdInterceptor implements HandlerInterceptor {

    public static final String CORRELATION_ID_HEADER_NAME = "X-Correlation-Id";
    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{"
            + SecurityConstants.CORRELATION_ID_MIN_LENGTH + "," + SecurityConstants.CORRELATION_ID_MAX_LENGTH + "}$");
    private static final int CORRELATION_ID_LENGTH = SecurityConstants.CORRELATION_ID_MIN_LENGTH;

    @Override
    public boolean preHandle(
            @NotNull final HttpServletRequest request,
            final HttpServletResponse response,
            @NotNull final Object handler) {
        response.setHeader(CORRELATION_ID_HEADER_NAME, resolveValidCorrelationId(request));
        return true;
    }

    @Override
    public void afterCompletion(
            @NotNull final HttpServletRequest request,
            @NotNull final HttpServletResponse response,
            @NotNull final Object handler,
            @NotNull final Exception ex) {
        // do nothing
    }

    private String resolveValidCorrelationId(final HttpServletRequest request) {
        var correlationId = request.getHeader(CORRELATION_ID_HEADER_NAME);
        var validCorrelationId = (correlationId != null
                        && CORRELATION_ID_PATTERN.matcher(correlationId).matches())
                ? correlationId
                : generateCorrelationId();

        MDC.put("_correlation_id", validCorrelationId);

        if (!validCorrelationId.equals(correlationId)) {
            var uri = request.getRequestURI();
            var message = "Correlation ID '" + StringEscapeUtils.escapeJava(correlationId)
                    + "' isn't valid, generated correlationId='" + validCorrelationId + "', url='" + uri + "'";
            if (StringUtils.isBlank(correlationId)) {
                log.debug(message);
            } else {
                log.error(message);
            }
        }

        return validCorrelationId;
    }

    public static String generateCorrelationId() {
        String traceId = TraceContextUtils.getTraceId();
        if (traceId == null) {
            return generateRandomCorrelationId();
        }
        return traceId;
    }

    private static String generateRandomCorrelationId() {
        return RandomStringUtils.randomAlphanumeric(CORRELATION_ID_LENGTH);
    }
}
