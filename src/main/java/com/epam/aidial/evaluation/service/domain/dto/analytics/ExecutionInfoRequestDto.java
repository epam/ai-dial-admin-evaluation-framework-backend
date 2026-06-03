package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionInfoRequestDto {

    @NotNull(message = "Execution status is required")
    private ExecutionStatus status;

    @NotNull(message = "startedAt is required")
    private Long startedAt;

    @NotNull(message = "completedAt is required")
    private Long completedAt;

    private String traceId;

    private Integer retryCount;
    private Object logDetails;
}
