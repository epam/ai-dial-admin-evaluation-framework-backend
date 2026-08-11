package com.epam.aidial.evaluation.web.path;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UriUtils;

/**
 * Resolves the part of the request path matched by a trailing {@code /**} in the handler mapping
 * pattern. Required for path values that may themselves contain slashes — e.g. DIAL Core deployment
 * IDs such as {@code applications/public/Quick App with RAG__0.0.1} — which a plain
 * {@code @PathVariable} cannot capture.
 *
 * <p>The tail is taken from the raw (still percent-encoded) lookup path and decoded exactly once
 * here, so {@code %20} becomes a space and an intra-segment {@code %2F} becomes a slash. Unlike
 * {@link java.net.URLDecoder}, {@code +} is preserved literally, which is the correct reading of a
 * plus sign in a URL path component.
 */
@Component
@LogExecution
public class WildcardPathResolver {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * Resolves the decoded path tail matched by the mapping's trailing wildcard.
     *
     * @param request current request, matched by a pattern ending with {@code /**}
     * @return decoded tail, or an empty string when the request carries no tail
     * @throws ValidationException if the tail contains a malformed percent-encoded sequence
     * @throws IllegalStateException if the request was not matched by a handler mapping pattern
     */
    public String resolveTail(HttpServletRequest request) {
        final String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            throw new IllegalStateException(
                    "No best matching pattern attribute on request; WildcardPathResolver is usable "
                            + "only from a Spring MVC handler method");
        }
        final String tail = PATH_MATCHER.extractPathWithinPattern(pattern, lookupPath(request));
        return decode(tail);
    }

    private static String lookupPath(HttpServletRequest request) {
        final String pathWithinMapping =
                (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (pathWithinMapping != null) {
            return pathWithinMapping;
        }
        final String requestUri = request.getRequestURI();
        final String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static String decode(String tail) {
        try {
            return UriUtils.decode(tail, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Malformed percent-encoded sequence in request path: " + tail);
        }
    }
}
