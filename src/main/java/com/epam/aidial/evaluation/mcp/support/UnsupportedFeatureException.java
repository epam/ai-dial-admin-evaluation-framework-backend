package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.mcp.constants.McpToolDescriptions;

/**
 * Thrown by {@link UnsupportedFeatureGuard} when a tool call sets a forward-compatible field (e.g.
 * {@code additionalRequests}, {@code multiTurnData}, an {@code MCP_TOOL} suite field) that this
 * version does not support. Translated by {@link McpToolErrorTranslator} to
 * {@link com.epam.aidial.evaluation.mcp.model.McpErrorCode#NOT_SUPPORTED}.
 */
public class UnsupportedFeatureException extends RuntimeException {

    public UnsupportedFeatureException(String feature) {
        super(McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE + " " + feature);
    }
}
