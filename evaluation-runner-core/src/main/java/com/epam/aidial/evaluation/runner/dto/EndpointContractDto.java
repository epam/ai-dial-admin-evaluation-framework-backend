package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(example = "POST")
    private HttpMethod method;

    @NotBlank
    @Pattern(regexp = "^/[^\\s]*$", message = "relativeUrlPattern must be a path without whitespace")
    @Schema(example = "/chat/completions")
    private String relativeUrlPattern;

    @Size(max = 255)
    @Schema(example = "createCompletion")
    private String operationId;

    @Valid
    @Schema(
            example =
                    "[{\"name\":\"model\",\"in\":\"QUERY\",\"required\":true,\"schema\":{\"type\":\"string\"}},"
                            + "{\"name\":\"temperature\",\"in\":\"QUERY\",\"required\":false,\"schema\":{\"type\":\"number\"}}]")
    private List<ParameterDefinitionDto> parameters;

    @Valid
    private RequestBodySchemaDto requestBodySchema;

    @Schema(
            example = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
                    + "\"choices\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},"
                    + "\"usage\":{\"type\":\"object\",\"properties\":{\"prompt_tokens\":{\"type\":\"integer\"},"
                    + "\"completion_tokens\":{\"type\":\"integer\"}}}}}")
    private Map<String, Object> responseBodySchema;
}
