package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.List;

/**
 * Result of parsing an SSE stream via {@link SseEventParser}.
 *
 * @param events            parsed events in order of receipt
 * @param status            {@code SUCCESS}, {@code TIMEOUT}, or {@code ERROR}
 * @param truncationWarning non-null when parsing was stopped due to size limit
 */
public record SseParseResult(List<SseEvent> events, ExecutionStatus status, String truncationWarning) {}
