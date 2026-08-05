package com.epam.aidial.evaluation.cli.client.source.dto;

import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local mirror of the EF backend's {@code TestSuiteUpdateResultDto} — the response shape for both
 * {@code PUT /api/v1/test-suites/{id}} and {@code POST /api/v1/test-suites/{id}/clone}.
 *
 * <p>Manually kept in sync with
 * {@code com.epam.aidial.evaluation.runner.dto.TestSuiteUpdateResultDto}. {@code
 * revalidationTask} is deliberately omitted — the clone endpoint's own contract guarantees it is
 * always {@code null} (validation runs synchronously during clone), and unknown JSON properties are
 * tolerated ({@code DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES} is disabled).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteUpdateResultDto {

    private TestSuiteResponseDto suite;
}
