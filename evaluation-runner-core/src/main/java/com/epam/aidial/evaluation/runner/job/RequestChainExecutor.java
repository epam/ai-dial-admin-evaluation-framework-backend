package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.RequestExecutionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Executes an {@link HttpSuite}'s request chain — {@link HttpSuite#chain()} (request #0 = the suite's own
 * request fields, followed by the {@code additionalRequests} in order) (Decision 1/8/10 of the {@code
 * add-multi-request-suite} change's {@code design.md}). Requests run strictly sequentially: one accumulated
 * frame is threaded from each request into the next via {@link TurnLoopExecutor#execute}'s {@code
 * initialFrame} parameter and {@link RequestExecutionResult#accumulatedFrame()}, every request's rows are
 * concatenated in chain order, and the chain stops at the first request whose {@link
 * RequestExecutionResult#aborted()} is {@code true} — that
 * request's own rows (including its failing row, when one was issued) are still returned; no later request
 * is invoked. A single-request chain ({@code additionalRequests} empty) executes exactly one {@link
 * RequestExecutionSpec} with {@code totalRequests = 1}, so {@link TurnLoopExecutor} never stamps
 * {@code requestIndex}/{@code totalRequests} (Decision 9) and this class is a no-op wrapper around the
 * pre-existing single-request path.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class RequestChainExecutor {

    private final TurnLoopExecutor turnLoopExecutor;

    /**
     * Runs the whole chain for one test-case repetition, returning every persisted row in chain order.
     * {@code traceId} is the test-case-run span's id, shared by every row of every request.
     */
    public List<TestCaseRunResult> execute(
            TestCaseRunInput input,
            HttpSuite suite,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs) {

        final List<RequestExecutionSpec> specs = suite.chain();
        final List<TestCaseRunResult> rows = new ArrayList<>();
        Map<String, Object> accumulatedFrame = Map.of();

        for (RequestExecutionSpec spec : specs) {
            final RequestExecutionResult result = turnLoopExecutor.execute(
                    input, context, suite.target(), runIndex, spec, accumulatedFrame, traceId, execStartedAtMs);
            rows.addAll(result.rows());
            accumulatedFrame = result.accumulatedFrame();
            if (result.aborted()) {
                break;
            }
        }
        return rows;
    }
}
