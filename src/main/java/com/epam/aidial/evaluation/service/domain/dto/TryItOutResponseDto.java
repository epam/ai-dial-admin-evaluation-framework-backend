package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
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
public class TryItOutResponseDto {

    /** The last executed turn's resolved request; the only turn for a single-turn invocation. */
    private ResolvedRequestDto resolvedRequest;

    /** The last executed turn's response; the only turn for a single-turn invocation. */
    private TryItOutCoreResponseDto response;

    private Long durationMs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Grafana Explore URL for this trace (present only when Grafana integration is configured)",
            example = "http://grafana:3000/explore?...")
    private String grafanaTraceUrl;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Every turn of a multi-turn sequence, in order (including the last turn, which "
                    + "duplicates the top-level resolvedRequest/response/durationMs/traceId/grafanaTraceUrl). "
                    + "Omitted for a single-turn invocation, including a multi-turn test case that collapses "
                    + "to a single turn.")
    private List<TryItOutResponseDto> history;
}
