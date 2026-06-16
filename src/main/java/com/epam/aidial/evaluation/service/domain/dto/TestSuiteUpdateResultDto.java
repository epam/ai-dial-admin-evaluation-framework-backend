package com.epam.aidial.evaluation.service.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of TestSuite PUT: updated suite and optional revalidation task when schema changed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteUpdateResultDto {

    private TestSuiteResponseDto suite;
    /**
     * Non-null when schema changed and async re-validation was started (client should get 202).
     */
    private RevalidationTaskDto revalidationTask;
}
