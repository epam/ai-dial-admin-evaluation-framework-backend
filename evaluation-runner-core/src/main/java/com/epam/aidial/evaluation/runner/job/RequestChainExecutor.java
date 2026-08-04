package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Executes a DEPLOYMENT suite's request chain — request #0 (the suite's own {@code endpointRef}/{@code
 * requestTemplate}/{@code responseColumns}/{@code inputBindings}, labelled by {@code snapshotRequestName})
 * followed by {@code snapshotAdditionalRequests} in order (Decision 1/8/10 of the {@code
 * add-multi-request-suite} change's {@code design.md}). Requests run strictly sequentially: one accumulated
 * frame is threaded from each request into the next via {@link RequestExecutionSpec#initialFrame()}/{@link
 * RequestExecutionResult#accumulatedFrame()}, every request's rows are concatenated in chain order, and the
 * chain stops at the first request whose {@link RequestExecutionResult#aborted()} is {@code true} — that
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
            TestCaseRunInput input, EvaluationContext context, int runIndex, String traceId, long execStartedAtMs) {

        final List<RequestExecutionSpec> specs = buildSpecs(context);
        final List<TestCaseRunResult> rows = new ArrayList<>();
        Map<String, Object> accumulatedFrame = Map.of();

        for (RequestExecutionSpec baseSpec : specs) {
            final RequestExecutionSpec spec = baseSpec.withInitialFrame(accumulatedFrame);
            final RequestExecutionResult result =
                    turnLoopExecutor.execute(input, context, runIndex, spec, traceId, execStartedAtMs);
            rows.addAll(result.rows());
            accumulatedFrame = result.accumulatedFrame();
            if (result.aborted()) {
                break;
            }
        }
        return rows;
    }

    /**
     * Builds the ordered chain of {@link RequestExecutionSpec}s from the run snapshot: spec 0 from the
     * context's singular {@code snapshot*} fields plus {@code snapshotRequestName}, specs 1..N from {@code
     * snapshotAdditionalRequests} in order. {@code initialFrame} is left empty here — {@link #execute}
     * seeds each spec's real initial frame just before invoking it.
     */
    private List<RequestExecutionSpec> buildSpecs(EvaluationContext context) {
        final List<RequestDefinitionDto> additionalRequests = context.getSnapshotAdditionalRequests();
        final int additionalCount = additionalRequests != null ? additionalRequests.size() : 0;
        final int totalRequests = 1 + additionalCount;

        final List<RequestExecutionSpec> specs = new ArrayList<>(totalRequests);
        specs.add(new RequestExecutionSpec(
                0,
                totalRequests,
                context.getSnapshotRequestName(),
                context.getSnapshotEndpointRef(),
                context.getSnapshotRequestTemplate(),
                context.getSnapshotInputBindings(),
                context.getSnapshotResponseColumns(),
                Map.of()));

        for (int i = 0; i < additionalCount; i++) {
            // Invariant: additionalRequests never contains a null element here. TestSuiteRequestValidator
            // rejects a null chain element with a hard 400 at write time (create/update/clone), so a
            // persisted snapshot is guaranteed null-element-free by the time it reaches this run-time path
            // — no defensive null-skip needed.
            final RequestDefinitionDto definition = additionalRequests.get(i);
            specs.add(new RequestExecutionSpec(
                    i + 1,
                    totalRequests,
                    definition.getName(),
                    definition.getEndpointRef(),
                    definition.getRequestTemplate(),
                    definition.getInputBindings() != null ? definition.getInputBindings() : List.of(),
                    definition.getResponseColumns() != null ? definition.getResponseColumns() : List.of(),
                    Map.of()));
        }
        return specs;
    }
}
