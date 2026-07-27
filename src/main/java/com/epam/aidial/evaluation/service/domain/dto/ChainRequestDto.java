package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One element of a suite's {@code additionalRequests} chain — a complete request spec, independent of
 * the suite's flat request-0 configuration. Discriminated by {@code type} following the
 * {@code RequestBodyDto} / {@code MetricBindingSourceDto} pattern; an absent {@code type} deserializes
 * as {@link HttpChainRequestDto} so the common case needs no discriminator.
 *
 * <p>{@code deploymentRef} is deliberately absent: it stays suite-level, because every request in a
 * chain targets the same deployment. {@code endpointRef} is per-element because chain requests hit
 * different paths and methods with different body schemas — a shared {@code endpointRef} would
 * validate every request's body against request 0's schema.
 */
@Data
@NoArgsConstructor
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type",
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        defaultImpl = HttpChainRequestDto.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = HttpChainRequestDto.class, name = "HTTP"),
    @JsonSubTypes.Type(value = McpToolChainRequestDto.class, name = "MCP_TOOL")
})
@Schema(
        description = "One request in a multi-request suite's chain. Discriminated by `type`; "
                + "an absent `type` means `HTTP`.")
public abstract class ChainRequestDto {

    @Size(max = 255)
    @Schema(
            description = "Optional human-readable label for this request. Defaulted to `request-{n}` "
                    + "(1-based chain position) when absent. Labels must be unique across the resolved chain "
                    + "and are the preferred, reorder-safe way to target a metric with `condition`.",
            example = "invoke")
    private String label;

    @Valid
    @Schema(description = "This request's own endpoint contract — method, relative URL, parameters, body schema.")
    private EndpointContractDto endpointRef;

    @Valid
    @Schema(description = "This request's own request template.")
    private RequestTemplateDto requestTemplate;

    @Valid
    @Schema(
            description = "This request's own input bindings. May use `responseField` to consume a response "
                    + "column extracted by a strictly earlier chain request.")
    private List<InputBindingDto> inputBindings;

    @Valid
    @Size(max = 50)
    @Schema(
            description = "Response columns extracted from this request's response. Names must be unique "
                    + "across the whole chain, so they need no request qualification anywhere downstream.")
    private List<ResponseColumnDefinitionDto> responseColumns;

    public abstract ChainRequestType getType();
}
