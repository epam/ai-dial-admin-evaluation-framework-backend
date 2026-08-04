package com.epam.aidial.evaluation.cli.model;

import com.epam.aidial.evaluation.cli.client.source.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted bundle produced by the {@code fetch} step. Contains a suite's configuration and its
 * full list of test cases, ready for use by the {@code run} step without further source EF calls.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuiteFetchBundle {

    /** The source suite UUID from which this bundle was fetched. */
    private UUID sourceSuiteId;

    /** The destination (cloned) suite UUID that will receive the imported results. */
    private UUID destinationSuiteId;

    /** The suite configuration as returned by the source EF. */
    private TestSuiteResponseDto suite;

    /** All test cases from the suite's bound dataset. */
    private List<TestCaseResponseDto> testCases;
}
