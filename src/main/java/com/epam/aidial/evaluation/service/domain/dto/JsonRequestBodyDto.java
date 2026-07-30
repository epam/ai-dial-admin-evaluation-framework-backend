package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class JsonRequestBodyDto extends RequestBodyDto {

    /**
     * Either a legacy structural JSON object (a {@code Map}, resolved via {@code ${{}}} placeholder
     * substitution then evaluated as JSONata — JSON is a syntactic subset of JSONata, so a plain
     * object echoes itself) or a JSONata source expression ({@code String}, {@code ${{}}} placeholders
     * preprocessed first, then evaluated directly). Both converge on the same evaluation path.
     */
    @Schema(
            description = "Request body template: a legacy JSON object (Map) or a JSONata source expression "
                    + "(String). Both are JSONata-evaluated before being sent — a plain JSON object evaluates "
                    + "to itself.",
            oneOf = {Map.class, String.class},
            example = "{ \"model\": \"gpt-4\", \"messages\": $append($history, [{\"role\": \"user\", \"content\": "
                    + "\"${{question}}\"}]), \"stream\": false }")
    private Object content;

    @Override
    public String getContentType() {
        return "application/json";
    }
}
