package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;
import java.util.Map;

/**
 * Outcome of executing one request of a chain (Decision 8 of the {@code add-multi-request-suite} change's
 * {@code design.md}): the rows persisted for this request's turns, the accumulated frame this request ended
 * with (becomes the next request's {@link RequestExecutionSpec#initialFrame()}), and whether this request
 * aborted the run — a fail-fast turn failure (or defensive empty-turn-plan/cancellation outcome) that must
 * stop {@link RequestChainExecutor} from invoking any later request in the chain. Rows already produced by
 * this request (including a failing row itself, when one was issued) are still returned and persisted.
 */
public record RequestExecutionResult(
        List<TestCaseRunResult> rows, Map<String, Object> accumulatedFrame, boolean aborted) {}
