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
public class BatchWriteRequestDto {

    @NotNull(message = "testSuiteId is required")
    private UUID testSuiteId;

    @NotNull(message = "testSuiteRunId is required")
    private UUID testSuiteRunId;

    @NotEmpty(message = "results must not be empty")
    @Valid
    private List<TestCaseRunResultItemDto> results;
}
