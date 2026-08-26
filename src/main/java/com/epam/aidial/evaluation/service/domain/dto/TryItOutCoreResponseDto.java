package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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

    /**
     * The SSE stream's terminal parse status when it was NOT {@code SUCCESS} — {@code TIMEOUT} (idle
     * timeout or absolute cap crossed) or {@code ERROR} (read failure or size limit exceeded). Such an
     * invocation counts as failed: response-column extraction is skipped and a request chain stops here,
     * exactly as a non-2xx status would. {@code null} (omitted) for non-SSE responses and for streams
     * that completed normally.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Terminal parse status of an SSE stream that did not complete normally "
                    + "(TIMEOUT or ERROR). Such an invocation is treated as failed: no response-column "
                    + "extraction, and a request chain stops here. Omitted for non-SSE responses and for "
                    + "streams that completed normally.",
            example = "TIMEOUT")
    private ExecutionStatus streamingStatus;

    /**
     * Human-readable reason a stream was cut short (currently: accumulated bytes exceeded the configured
     * response-size limit). {@code null} (omitted) when the stream was not truncated.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Why the SSE stream was cut short (e.g. the response-size limit was reached). "
                    + "Omitted when the stream was not truncated.",
            example = "Response truncated: accumulated 10485760 bytes, limit 10485760")
    private String truncationWarning;
}
