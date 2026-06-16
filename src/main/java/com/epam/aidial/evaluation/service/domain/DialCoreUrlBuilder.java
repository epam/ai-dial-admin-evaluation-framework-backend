package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.Set;
import org.springframework.stereotype.Component;

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

    private static final Set<String> OPENAI_STANDARD_PATHS = Set.of("/chat/completions", "/embeddings");

    /**
     * Builds the full path for DIAL Core invocation.
     *
     * @param deploymentId deployment identifier from deploymentRef
     * @param resolvedUrl  resolved URL from template substitution
     * @return full path to append to base URL
     */
    public String buildUrl(String deploymentId, String resolvedUrl) {
        if (OPENAI_STANDARD_PATHS.contains(resolvedUrl)) {
            return "/openai/deployments/" + deploymentId + resolvedUrl;
        }
        return "/v1/deployments/" + deploymentId + "/route" + resolvedUrl;
    }
}
