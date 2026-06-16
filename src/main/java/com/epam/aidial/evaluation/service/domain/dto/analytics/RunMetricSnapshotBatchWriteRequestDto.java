package com.epam.aidial.evaluation.service.domain.dto.analytics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunMetricSnapshotBatchWriteRequestDto {

    @NotNull(message = "testSuiteRunId is required")
    private UUID testSuiteRunId;

    @NotNull(message = "computationId is required")
    private UUID computationId;

    @NotNull(message = "computedAtMs is required")
    private Long computedAtMs;

    @NotEmpty(message = "snapshots must not be empty")
    @Valid
    private List<RunMetricSnapshotBatchWriteItemDto> snapshots;
}
