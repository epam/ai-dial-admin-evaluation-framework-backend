package com.epam.aidial.evaluation.runner.job;

/**
 * Error codes written into the {@code {"error":{"code":...}}} response-body envelope of a Phase 1 result row
 * (see {@link DeploymentInvocationSupport#buildErrorEnvelope}). Defined once here for the whole execution
 * path so the DEPLOYMENT ({@link TurnLoopExecutor}, {@link DeploymentTurnInvoker}) and MCP
 * ({@link EvaluationWorker}) branches cannot drift apart — these values are part of the persisted
 * {@code response_body} contract read by clients and exports.
 */
public final class ExecutionErrorCodes {

    /** Building the request for a turn failed (URL/header/query/body template resolution). */
    public static final String REQUEST_RESOLUTION_ERROR = "REQUEST_RESOLUTION_ERROR";

    /** The request body's JSONata expression failed to evaluate, or evaluated to a non-object. */
    public static final String REQUEST_BODY_EVALUATION_ERROR = "REQUEST_BODY_EVALUATION_ERROR";

    /** The deployment HTTP call failed at transport level (network error or timeout). */
    public static final String INVOCATION_ERROR = "INVOCATION_ERROR";

    /** Resolving an MCP tool call's arguments failed. */
    public static final String MCP_RESOLUTION_ERROR = "MCP_RESOLUTION_ERROR";

    /** The MCP tool call itself failed (transport error or gateway timeout). */
    public static final String MCP_INVOCATION_ERROR = "MCP_INVOCATION_ERROR";

    private ExecutionErrorCodes() {}
}
