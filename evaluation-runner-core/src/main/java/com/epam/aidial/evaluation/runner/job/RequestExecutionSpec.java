package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import java.util.List;

/**
 * One request's execution parameters within a suite's request chain (Decision 8 of the {@code
 * add-multi-request-suite} change's {@code design.md}) — a context-object carrier used to generalize {@link
 * TurnLoopExecutor#execute} instead of growing its parameter list past the point of maintainability. The
 * accumulated frame carried in from earlier requests in the chain is threaded separately, as {@link
 * TurnLoopExecutor#execute}'s own {@code initialFrame} parameter, rather than as a field here.
 *
 * @param requestIndex 0-based position of this request within the chain
 * @param totalRequests chain length (1 for a single-request suite)
 * @param name user-facing label for this request — the suite-level {@code requestName} for request #0, the
 *     {@code RequestDefinitionDto.name} for an additional request; {@code null} when unlabelled
 * @param endpointRef this request's endpoint contract
 * @param requestTemplate this request's URL/query/header/body template
 * @param inputBindings this request's input bindings
 * @param responseColumns this request's response column definitions
 */
public record RequestExecutionSpec(
        int requestIndex,
        int totalRequests,
        String name,
        EndpointContractDto endpointRef,
        RequestTemplateDto requestTemplate,
        List<InputBindingDto> inputBindings,
        List<ResponseColumnDefinitionDto> responseColumns) {}
