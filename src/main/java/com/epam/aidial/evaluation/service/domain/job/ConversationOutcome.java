package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;

/**
 * Terminal state of a completed (or aborted) multi-step conversation, carrying the values the turn loop
 * accumulates: the final execution status, the last turn's HTTP status code and raw request/response
 * bodies, the last turn's retry count, the column-major extracted-columns JSON, and the flat per-turn
 * extraction-warnings JSON (each warning tagged with its {@code stepIndex}). Consumed by
 * {@link MultiStepResultAssembler#success} to build the persisted {@code TestCaseRunResult}.
 */
public record ConversationOutcome(
        ExecutionStatus status,
        Integer lastStatusCode,
        String lastRequestBodyJson,
        String lastResponseBodyJson,
        int lastRetryCount,
        String extractedColumnsJson,
        String extractionWarningsJson) {}
