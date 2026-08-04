package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import java.util.List;
import java.util.Map;

/**
 * One request's execution parameters within a suite's request chain (Decision 8 of the {@code
 * add-multi-request-suite} change's {@code design.md}) — a context-object carrier used to generalize {@link
 * TurnLoopExecutor#execute} instead of growing its parameter list past the point of maintainability.
 *
 * @param requestIndex 0-based position of this request within the chain
 * @param totalRequests chain length (1 for a single-request suite)
 * @param name user-facing label for this request — the suite-level {@code requestName} for request #0, the
 *     {@code RequestDefinitionDto.name} for an additional request; {@code null} when unlabelled
 * @param endpointRef this request's endpoint contract
 * @param requestTemplate this request's URL/query/header/body template
 * @param inputBindings this request's input bindings
 * @param responseColumns this request's response column definitions
 * @param initialFrame the accumulated frame carried in from earlier requests in the chain (empty for
 *     request #0); seeds this request's turn-0 resolution frame and is the base onto which every row's
 *     persisted {@code extracted_columns} is merged
 */
public record RequestExecutionSpec(
        int requestIndex,
        int totalRequests,
        String name,
        EndpointContractDto endpointRef,
        RequestTemplateDto requestTemplate,
        List<InputBindingDto> inputBindings,
        List<ResponseColumnDefinitionDto> responseColumns,
        Map<String, Object> initialFrame) {

    /**
     * Returns a copy of this spec with {@code initialFrame} replaced. Used by {@link RequestChainExecutor}
     * to thread the accumulated frame returned by one request into the next request's spec without
     * repeating the other seven fields.
     */
    public RequestExecutionSpec withInitialFrame(Map<String, Object> frame) {
        return new RequestExecutionSpec(
                requestIndex, totalRequests, name, endpointRef, requestTemplate, inputBindings, responseColumns, frame);
    }
}
