package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One additional request (index 1..N) in a suite's request chain. Request #0 remains the
 * suite's own {@code endpointRef}/{@code requestTemplate}/{@code responseColumns}/{@code
 * inputBindings} fields — this DTO carries the same shape for every request after it.
 *
 * <p>Deliberately excludes {@code deploymentRef}, MCP fields, {@code testCaseFilter} and {@code
 * overallScore} — those stay suite-level; the suite↔deployment relationship is 1-to-1.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestDefinitionDto {

    @Size(max = 255)
    private String name;

    @Valid
    private EndpointContractDto endpointRef;

    @Valid
    private RequestTemplateDto requestTemplate;

    @Valid
    private List<ResponseColumnDefinitionDto> responseColumns;

    @Valid
    private List<InputBindingDto> inputBindings;
}
