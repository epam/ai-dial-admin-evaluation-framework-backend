package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.util.List;

/**
 * Result of parsing an SSE stream via {@link SseEventParser}.
 *
 * @param events            parsed events in order of receipt
 * @param status            {@code SUCCESS}, {@code TIMEOUT}, or {@code ERROR}
 * @param truncationWarning non-null when parsing was stopped due to size limit
 */
public record SseParseResult(List<SseEvent> events, ExecutionStatus status, String truncationWarning) {}
