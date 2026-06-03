package com.epam.aidial.evaluation.service.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for a single parsed SSE event returned in {@link TryItOutCoreResponseDto}.
 * Provides frontend consumers with individual events for debugging purposes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseEventDto {

    /** SSE event type name (e.g., {@code "process_rules"}, {@code "message"}). */
    private String event;

    /** Parsed JSON payload if the data was valid JSON, raw string otherwise. */
    private Object data;
}
