package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpMethod;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointContractDto {

    @NotNull
    private HttpMethod method;

    @NotBlank
    @Pattern(regexp = "^/[^\\s]*$", message = "relativeUrlPattern must be a path without whitespace")
    private String relativeUrlPattern;

    @Size(max = 255)
    private String operationId;

    @Valid
    private List<ParameterDefinitionDto> parameters;

    @Valid
    private RequestBodySchemaDto requestBodySchema;

    private Map<String, Object> responseBodySchema;
}
