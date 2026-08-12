package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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
}
