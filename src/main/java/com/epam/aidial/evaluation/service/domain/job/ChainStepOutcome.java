package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.util.List;
import java.util.Map;

/**
 * Result of running one chain step, as the chain executor needs it: what to persist on this request's row,
 * and what to merge into the accumulated response-column map for later requests.
 *
 * @param status               this request's execution status; anything other than SUCCESS aborts the chain
 * @param statusCode           HTTP status, null when the call never produced a response (timeout, network error)
 * @param requestBodyJson      the resolved body actually sent, for analytics
 * @param responseBody         the raw response body
 * @param retryCount           retries performed for this request
 * @param extractedColumnsJson this request's OWN extracted columns as JSON — never the accumulated set, so a
 *                             row's {@code extracted_columns} contains only what its request produced
 * @param extractionWarningsJson this request's own extraction warnings as JSON
 * @param extractedValues      the same extracted columns as a typed map, for merging into the accumulator
 * @param unresolvedResponseFields response columns a {@code responseField} binding needed but that were
 *                             absent with no declared placeholder default — non-empty means the step was
 *                             not sent and the chain must abort
 * @param issued               whether this step actually issued its HTTP call. False only when it was
 *                             abandoned before sending — an unresolvable dependency, or a rate-limit token
 *                             wait interrupted by run cancellation. An un-issued step under cancellation
 *                             contributes no row (see {@code ChainExecutor}); an un-issued step with no
 *                             cancellation still writes its diagnostic ERROR row.
 */
public record ChainStepOutcome(
        ExecutionStatus status,
        Integer statusCode,
        String requestBodyJson,
        String responseBody,
        int retryCount,
        String extractedColumnsJson,
        String extractionWarningsJson,
        Map<String, Object> extractedValues,
        List<String> unresolvedResponseFields,
        boolean issued) {

    /** True when the step completed and the chain may continue to the next request. */
    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS;
    }

    /**
     * A step that was never sent because a {@code responseField} could not be resolved and its placeholder
     * declared no default. Persists as an ERROR row and aborts the chain, rather than firing a semantically
     * nonsense call that would likely return 200 and have metrics scored on garbage.
     */
    public static ChainStepOutcome unresolvedDependency(String requestBodyJson, List<String> missingColumns) {
        return new ChainStepOutcome(
                ExecutionStatus.ERROR,
                null,
                requestBodyJson,
                null,
                0,
                "{}",
                "[]",
                Map.of(),
                List.copyOf(missingColumns),
                false);
    }

    /**
     * A step that failed before or during its call, with nothing extracted. {@code issued} distinguishes a
     * genuine call failure from a step abandoned at the rate-limit gate, which must not leave a row behind
     * when the run is being cancelled.
     */
    public static ChainStepOutcome failed(
            ExecutionStatus status,
            Integer statusCode,
            String requestBodyJson,
            String responseBody,
            int retryCount,
            boolean issued) {
        return new ChainStepOutcome(
                status, statusCode, requestBodyJson, responseBody, retryCount, "{}", "[]", Map.of(), List.of(), issued);
    }
}
