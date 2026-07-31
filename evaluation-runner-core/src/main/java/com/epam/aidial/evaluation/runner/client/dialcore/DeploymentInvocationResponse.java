package com.epam.aidial.evaluation.runner.client.dialcore;

/**
 * Response from a DIAL Core deployment invocation.
 * Kept in the client layer to avoid depending on service-layer DTOs.
 *
 * @param statusCode HTTP status code from DIAL Core
 * @param body       parsed JSON (Map/List/String/Number/Boolean/null) or raw string if not valid JSON
 */
public record DeploymentInvocationResponse(int statusCode, Object body) {}
