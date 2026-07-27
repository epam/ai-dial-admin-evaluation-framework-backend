package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import java.util.List;

/**
 * One request of a suite's normalized chain — the uniform shape every consumer works against, produced
 * by {@link ChainNormalizer}. Element 0 is synthesized from the suite's flat
 * {@code endpointRef}/{@code requestTemplate}/{@code inputBindings}/{@code responseColumns} and
 * {@code requestLabel}; elements {@code 1..N-1} come from {@code additionalRequests} in order.
 *
 * <p>Because the chain is normalized, no consumer branches between "the flat fields" and "the array" —
 * that {@code if} would otherwise replicate into per-element validation, execution, {@code request_index}
 * assignment, export column planning, and query-schema discovery, each a place to skip request 0 or
 * misassign an index.
 *
 * <p>{@code label} is always non-null (defaulted during normalization) and {@code index} is always the
 * element's contiguous 0-based chain position, so both can be written to a result row unconditionally.
 * There is no {@code deploymentRef} here: it stays suite-level.
 */
public record RequestSpec(
        int index,
        String label,
        ChainRequestType type,
        EndpointContractDto endpointRef,
        RequestTemplateDto requestTemplate,
        List<InputBindingDto> inputBindings,
        List<ResponseColumnDefinitionDto> responseColumns) {

    /** Bindings, never null — an unconfigured chain element resolves against no bindings rather than NPEing. */
    public List<InputBindingDto> safeInputBindings() {
        return inputBindings != null ? inputBindings : List.of();
    }

    /** Response columns, never null. */
    public List<ResponseColumnDefinitionDto> safeResponseColumns() {
        return responseColumns != null ? responseColumns : List.of();
    }
}
