package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.SuiteType;
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

    private static final Set<String> OPENAI_STANDARD_PATHS = Set.of("/chat/completions", "/embeddings");

    /**
     * Builds the full path for DIAL Core invocation.
     *
     * @param deploymentId deployment identifier from deploymentRef
     * @param resolvedUrl  resolved URL from template substitution
     * @return full path to append to base URL
     */
    public String buildUrl(String deploymentId, String resolvedUrl) {
        return buildUrl(SuiteType.DEPLOYMENT, deploymentId, resolvedUrl);
    }

    public String buildUrl(SuiteType suiteType, String deploymentId, String resolvedUrl) {
        if (suiteType == SuiteType.ROUTE) {
            validateRoutePath(resolvedUrl);
            return resolvedUrl;
        }
        if (suiteType != SuiteType.DEPLOYMENT) {
            throw new ValidationException("Unsupported HTTP suite type: " + suiteType);
        }
        if (OPENAI_STANDARD_PATHS.contains(resolvedUrl)) {
            return "/openai/deployments/" + deploymentId + resolvedUrl;
        }
        if (StringUtils.startsWithIgnoreCase(resolvedUrl, ModelSelectingEndpointPaths.OPENAI_RESPONSES)
                || ModelSelectingEndpointPaths.ANTHROPIC_MESSAGES.equals(resolvedUrl)) {
            return resolvedUrl;
        }
        return "/v1/deployments/" + deploymentId + "/route" + resolvedUrl;
    }

    private void validateRoutePath(String resolvedUrl) {
        if (!StringUtils.hasText(resolvedUrl) || !resolvedUrl.startsWith("/") || resolvedUrl.startsWith("//")) {
            throw new ValidationException("ROUTE urlTemplate must resolve to a root-relative DIAL Core path");
        }
        if (resolvedUrl.indexOf('?') >= 0 || resolvedUrl.indexOf('#') >= 0) {
            throw new ValidationException(
                    "ROUTE urlTemplate path must not contain query or fragment data; use queryParams instead");
        }
        if (resolvedUrl.indexOf('\\') >= 0 || resolvedUrl.chars().anyMatch(Character::isISOControl)) {
            throw new ValidationException("ROUTE urlTemplate path contains an unsupported character");
        }
    }
}
