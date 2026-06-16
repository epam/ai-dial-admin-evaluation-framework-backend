package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
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
public class ExecutionInfoResponseDto {

    private ExecutionStatus status;
    private Long startedAt;
    private Long completedAt;
    private Long durationMs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Grafana Explore URL for this trace (present only when Grafana integration is configured)",
            example = "http://grafana:3000/explore?...")
    private String grafanaTraceUrl;

    @Schema(description = "Number of retry attempts before final outcome (0 = no retries)", example = "2")
    private Integer retryCount;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Structured retry attempt log (populated only when retryCount > 0)")
    private Object logDetails;
}
