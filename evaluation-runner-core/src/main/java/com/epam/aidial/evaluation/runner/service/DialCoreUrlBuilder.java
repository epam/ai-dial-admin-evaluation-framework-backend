package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the full DIAL Core URL for deployment invocation based on the resolved URL.
 *
 * <p>The resolved URL comes from template variable substitution (not {@code endpointRef.relativeUrlPattern}),
 * so a template like {@code /chat/${{action}}} resolving to {@code /chat/completions}
 * is routed correctly through the standard OpenAI path.
 */
@Component
@LogExecution
public class DialCoreUrlBuilder {

    private static final Set<String> CHAT_COMPLETION_STANDARD_PATHS = Set.of("/chat/completions", "/embeddings");
    private static final String RESPONSES_ROOT = "/openai/v1/responses";

    /**
     * Builds the full path for DIAL Core invocation.
     *
     * @param deploymentId deployment identifier from deploymentRef
     * @param resolvedUrl  resolved URL from template substitution
     * @return full path to append to base URL
     */
    public String buildUrl(String deploymentId, String resolvedUrl) {
        if (CHAT_COMPLETION_STANDARD_PATHS.contains(resolvedUrl)) {
            return "/openai/deployments/" + deploymentId + resolvedUrl;
        }
        if (StringUtils.startsWithIgnoreCase(resolvedUrl, RESPONSES_ROOT)) {
            return resolvedUrl;
        }
        return "/v1/deployments/" + deploymentId + "/route" + resolvedUrl;
    }
}
