package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;

/**
 * SPI for executing one request of a multi-request chain, selected by the chain element's
 * {@link ChainRequestType}. Implementations are collected by {@link ChainStepExecutorRegistry}, so adding a
 * new chain-request kind means dropping in a new bean — no edits to the chain executor.
 *
 * <p>The pre-existing single-request MCP path ({@code EvaluationWorker.executeMcp}) is deliberately NOT
 * routed through this SPI: refactoring a working executor to prove an abstraction risks regressing MCP
 * suites for no user-visible gain.
 */
public interface ChainStepExecutor {

    /** The chain element type this executor handles. Exactly one executor per type. */
    ChainRequestType supportedType();

    /**
     * Runs one chain request and reports what to persist and what to accumulate. Implementations must not
     * throw for ordinary request failures — a non-2xx, timeout, or unresolvable dependency is reported as a
     * non-SUCCESS {@link ChainStepOutcome} so the chain executor can persist an ERROR row and abort.
     */
    ChainStepOutcome execute(ChainStepRequest step);
}
