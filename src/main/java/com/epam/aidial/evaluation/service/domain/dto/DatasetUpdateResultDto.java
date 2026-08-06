package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteUpdateResultDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of Dataset PUT: updated dataset and optional revalidation task when test_case_schema changed.
 * Mirrors {@link TestSuiteUpdateResultDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetUpdateResultDto {

    private DatasetResponseDto dataset;
    /**
     * Non-null when {@code testCaseSchema} changed and async re-validation was started
     * (client should get 202 with the task).
     */
    private RevalidationTaskDto revalidationTask;
}
