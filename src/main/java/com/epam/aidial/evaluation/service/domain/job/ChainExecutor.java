package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.RequestSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a multi-request suite's chain for one test case: the requests strictly in chain order, as one unit,
 * inside the caller's single worker task and semaphore permit. Emits one {@link TestCaseRunResult} per
 * <b>executed</b> request (fewer than N on early abort).
 *
 * <p>The loop maintains an <b>accumulating map</b> of response column values extracted so far, seeded empty
 * and merged after each request. A later request's {@code responseField} binding resolves against that map,
 * so request 3 can consume request 0's session id — not merely its predecessor's output. Each persisted row,
 * however, carries only <b>its own</b> request's {@code extracted_columns}: the accumulated map is execution
 * state, not row content, which keeps the results grid and CSV export attributing each value to the request
 * that produced it.
 *
 * <p>Fail-fast, mirroring multi-turn: the first failing request persists one ERROR row and aborts; later
 * requests are never sent. Continuing would resolve a dependent placeholder to a default, fire a semantically
 * nonsense call, likely get a 200, and persist a SUCCESS row with meaningful-looking metric values computed
 * on garbage — a silently wrong result is worse than a missing one.
 *
 * <p>Because multi-request and multi-turn are mutually exclusive (rejected at run creation), every row here
 * carries {@code turnIndex = 0} / {@code totalTurns = 1} and the chain loop never sees {@code multiTurnData}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ChainExecutor {

    private final ChainStepExecutorRegistry stepExecutorRegistry;
    private final QuietJsonService jsonService;
    private final Clock clock;

    /**
     * Runs the run's frozen chain for one test case. {@code traceId} is the test-case-run span's id, shared by
     * every request row so all rows of one chain are correlated.
     */
    public List<TestCaseRunResult> execute(
            TestCaseRunInput input, EvaluationContext context, int runIndex, String traceId, long execStartedAtMs) {

        final List<RequestSpec> chain = context.getChain();
        if (chain == null || chain.isEmpty()) {
            log.warn("Chain executor invoked for run {} with an empty chain; nothing to execute", context.getRunId());
            return List.of();
        }

        final Map<String, Object> testCaseData = parseTestCaseData(input.getTestCaseData());
        final Map<String, Object> accumulated = new LinkedHashMap<>();
        final List<TestCaseRunResult> results = new ArrayList<>(chain.size());

        for (RequestSpec request : chain) {
            if (context.getCancellationSignal().get()) {
                // Cancelled mid-chain: per the "no synthetic rows for unfinished cases" contract, the
                // not-yet-executed request contributes no row at all.
                log.debug(
                        "Run {} cancelled before chain request {} of test case {}",
                        context.getRunId(),
                        request.index(),
                        input.getTestCaseId());
                break;
            }

            final long requestStart = clock.millis();
            final ChainStepOutcome outcome =
                    runStep(request, context, testCaseData, accumulated, input.getTestCaseId());
            final long requestEnd = clock.millis();

            // Same rule as the multi-turn sibling: a step abandoned at the rate-limit gate never sent anything,
            // so under cancellation it contributes no row — "no synthetic rows for unfinished cases". An
            // un-issued step with no cancellation (an unresolvable dependency) still writes its ERROR row,
            // which is the only record of why the chain aborted.
            if (outcome.issued() || !context.getCancellationSignal().get()) {
                results.add(buildRow(input, context, runIndex, traceId, request, outcome, requestStart, requestEnd));
            }

            if (!outcome.isSuccess()) {
                log.warn(
                        "Chain aborted for test case {} in run {} at request {} ('{}') with status {}; "
                                + "{} later request(s) not sent",
                        input.getTestCaseId(),
                        context.getRunId(),
                        request.index(),
                        request.label(),
                        outcome.status(),
                        chain.size() - request.index() - 1);
                break;
            }

            // Later value wins on name reuse. Unreachable through the API — chain-wide name uniqueness is
            // enforced at suite save — so this is only a defensive tiebreak.
            accumulated.putAll(outcome.extractedValues());
        }
        return results;
    }

    /**
     * Dispatches one step through the registry, converting an unexpected unchecked failure into an ERROR
     * outcome so a bug in one step aborts that chain rather than escaping to fail the whole worker with no
     * row written for the request that broke.
     */
    private ChainStepOutcome runStep(
            RequestSpec request,
            EvaluationContext context,
            Map<String, Object> testCaseData,
            Map<String, Object> accumulated,
            UUID testCaseId) {
        // Collections.unmodifiableMap over a defensive copy, NOT Map.copyOf: an extracted column whose JSONata
        // matched nothing is accumulated as an explicit null, and Map.copyOf rejects null values with an NPE
        // thrown outside the try below — which would escape as a whole-worker failure instead of this chain's
        // own fail-fast ERROR row.
        final ChainStepRequest step = new ChainStepRequest(
                request, context, testCaseData, Collections.unmodifiableMap(new LinkedHashMap<>(accumulated)));
        try {
            return stepExecutorRegistry.require(request.type()).execute(step);
        } catch (RuntimeException e) {
            log.warn(
                    "Chain request {} ('{}') failed for test case {}: {}",
                    request.index(),
                    request.label(),
                    testCaseId,
                    e.getMessage(),
                    e);
            // issued = true: an unexpected throw is a genuine failure of this step, not a step declined at the
            // gate, so its row MUST survive — it is the only trace of the bug.
            return ChainStepOutcome.failed(ExecutionStatus.ERROR, null, null, buildErrorEnvelope(e), 0, true);
        }
    }

    private TestCaseRunResult buildRow(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            RequestSpec request,
            ChainStepOutcome outcome,
            long requestStart,
            long requestEnd) {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .requestIndex(request.index())
                .requestLabel(request.label())
                // Inert for multi-request rows: the run-creation guard makes multi-request and multi-turn
                // mutually exclusive, so total_turns keeps its meaning as the test case's turn count.
                .turnIndex(0)
                .totalTurns(1)
                .testCaseData(input.getTestCaseData())
                .requestBody(outcome.requestBodyJson())
                .responseBody(outcome.responseBody())
                .responseStatusCode(outcome.statusCode())
                .executionStatus(outcome.status())
                .execStartedAtMs(requestStart)
                .execCompletedAtMs(requestEnd)
                .execDurationMs(requestEnd - requestStart)
                .traceId(traceId)
                .extractedColumns(outcome.extractedColumnsJson())
                .extractionWarnings(outcome.extractionWarningsJson())
                .retryCount(outcome.retryCount())
                .logDetails(buildLogDetails(outcome))
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    /**
     * Records an unresolvable-dependency abort in {@code log_details} so the reason is visible on the row
     * itself — a bare ERROR with no response body would otherwise look like a network failure.
     */
    private String buildLogDetails(ChainStepOutcome outcome) {
        if (outcome.unresolvedResponseFields().isEmpty()) {
            return null;
        }
        final var node = jsonService.createObjectNode();
        node.put(
                "error",
                "Unresolved response column(s) from earlier chain requests, with no declared placeholder default: "
                        + String.join(", ", outcome.unresolvedResponseFields()));
        return jsonService.writeOrToString(node);
    }

    private String buildErrorEnvelope(RuntimeException e) {
        final String message =
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return DeploymentInvocationSupport.errorEnvelope("CHAIN_STEP_ERROR", message, jsonService);
    }

    private Map<String, Object> parseTestCaseData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return Map.of();
        }
        return jsonService.readMapOrEmpty(dataJson);
    }
}
