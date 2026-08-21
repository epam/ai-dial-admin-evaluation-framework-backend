package com.epam.aidial.evaluation.cli.model;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted bundle produced by the {@code fetch} step. Contains a suite's configuration, its bound
 * dataset's test-case schema, and its full list of test cases, ready for use by the {@code run}
 * step without further source EF calls.
 *
 * <p>{@code testCaseSchema} is null when this bundle was persisted by an earlier CLI version that
 * did not yet fetch it (see {@code cli-multi-turn-multi-request-parity} design.md Decision 7) —
 * Jackson leaves the field {@code null} on load rather than failing, and {@code
 * RunOrchestrationService} decides whether that absence is safe to proceed with.
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

    /**
     * The bound dataset's test-case schema field definitions, including each field's {@code
     * perTurn} scope declaration. Null when fetched by a CLI version that predates this field.
     */
    private List<FieldDefinitionDto> testCaseSchema;
}
