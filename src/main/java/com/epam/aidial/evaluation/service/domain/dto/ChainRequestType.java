package com.epam.aidial.evaluation.service.domain.dto;

/**
 * Discriminator for a multi-request chain element. Selects which {@code ChainStepExecutor} runs the
 * element at run time and which validation rules apply at save time.
 *
 * <p>{@link #HTTP} is the only implemented kind. {@link #MCP_TOOL} exists so the chain model and the
 * step-executor registry have a real seam for MCP chaining without implementing it: an
 * {@code MCP_TOOL}-typed element is rejected at suite save with HTTP 400, and its step executor throws
 * {@code UnsupportedOperationException} as an unreachable-by-construction backstop. The pre-existing
 * single-request MCP path is unrelated and is not routed through the chain.
 */
public enum ChainRequestType {

    /** A plain HTTP request against the suite-level deployment. The default when {@code type} is absent. */
    HTTP,

    /** An MCP tool invocation. Accepted by the model, rejected at suite save — not yet implemented. */
    MCP_TOOL
}
