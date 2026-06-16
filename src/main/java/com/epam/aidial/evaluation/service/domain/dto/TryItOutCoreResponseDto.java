package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TryItOutCoreResponseDto {

    private int statusCode;

    /** Parsed JSON response body, or {@code {"events": [...]}} envelope for SSE responses. */
    private Object body;

    /**
     * {@code true} when the DIAL Core response was an SSE stream; {@code null} (omitted) for
     * non-SSE responses.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean streaming;

    /**
     * Parsed SSE events for frontend debugging. Present only when {@link #streaming} is
     * {@code true}; {@code null} (omitted from JSON) for non-SSE responses.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SseEventDto> events;
}
