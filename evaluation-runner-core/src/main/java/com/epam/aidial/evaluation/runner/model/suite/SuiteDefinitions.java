package com.epam.aidial.evaluation.runner.model.suite;

import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.model.target.HttpTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import com.epam.aidial.evaluation.runner.model.target.SuiteTargets;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a {@link SuiteDefinition} from the flat wire/snapshot representations.
 *
 * <p>Static on purpose: pure, dependency-free derivation over runner DTOs, in the same style as
 * {@link SuiteTargets}.
 */
public final class SuiteDefinitions {

    private SuiteDefinitions() {}

    /** Derives the definition from a persisted snapshot; list fields are taken as stored (possibly null). */
    public static SuiteDefinition fromSnapshot(SuiteSnapshotDto snapshot) {
        final SuiteTarget target = SuiteTargets.fromSnapshot(snapshot);
        return switch (target) {
            case HttpTarget h ->
                new HttpSuite(
                        h,
                        httpChain(
                                snapshot.getRequestName(),
                                snapshot.getEndpointRef(),
                                snapshot.getRequestTemplate(),
                                snapshot.getInputBindings(),
                                snapshot.getResponseColumns(),
                                snapshot.getAdditionalRequests()));
            case McpToolTarget m ->
                new McpSuite(
                        m, snapshot.getArgumentTemplate(), snapshot.getInputBindings(), snapshot.getResponseColumns());
        };
    }

    /** Derives the definition from a suite response DTO, defaulting null lists to empty. */
    public static SuiteDefinition fromSuiteResponse(TestSuiteResponseDto suite) {
        final SuiteTarget target = SuiteTargets.fromSuiteResponse(suite);
        final List<InputBindingDto> inputBindings =
                suite.getInputBindings() != null ? suite.getInputBindings() : List.of();
        final List<ResponseColumnDefinitionDto> responseColumns =
                suite.getResponseColumns() != null ? suite.getResponseColumns() : List.of();
        return switch (target) {
            case HttpTarget h ->
                new HttpSuite(
                        h,
                        httpChain(
                                suite.getRequestName(),
                                suite.getEndpointRef(),
                                suite.getRequestTemplate(),
                                inputBindings,
                                responseColumns,
                                suite.getAdditionalRequests() != null ? suite.getAdditionalRequests() : List.of()));
            case McpToolTarget m -> new McpSuite(m, suite.getArgumentTemplate(), inputBindings, responseColumns);
        };
    }

    /**
     * Builds the ordered request chain: spec 0 from the suite's own (singular) fields, specs 1..N from
     * {@code additionalRequests} in order, with a null {@code inputBindings}/{@code responseColumns} of an
     * additional request normalized to empty. A null {@code additionalRequests} means a single-request chain.
     * Request #0's values are passed through as given.
     */
    public static List<RequestExecutionSpec> httpChain(
            String requestName,
            EndpointContractDto endpointRef,
            RequestTemplateDto requestTemplate,
            List<InputBindingDto> inputBindings,
            List<ResponseColumnDefinitionDto> responseColumns,
            List<RequestDefinitionDto> additionalRequests) {
        final int additionalCount = additionalRequests != null ? additionalRequests.size() : 0;
        final int totalRequests = 1 + additionalCount;

        final List<RequestExecutionSpec> specs = new ArrayList<>(totalRequests);
        specs.add(new RequestExecutionSpec(
                0, totalRequests, requestName, endpointRef, requestTemplate, inputBindings, responseColumns));

        for (int i = 0; i < additionalCount; i++) {
            // Invariant: additionalRequests never contains a null element here. TestSuiteRequestValidator
            // rejects a null chain element with a hard 400 at write time (create/update/clone), so persisted
            // snapshots and entities are null-element-free by the time they reach this path.
            final RequestDefinitionDto definition = additionalRequests.get(i);
            specs.add(new RequestExecutionSpec(
                    i + 1,
                    totalRequests,
                    definition.getName(),
                    definition.getEndpointRef(),
                    definition.getRequestTemplate(),
                    definition.getInputBindings() != null ? definition.getInputBindings() : List.of(),
                    definition.getResponseColumns() != null ? definition.getResponseColumns() : List.of()));
        }
        return specs;
    }
}
