package com.epam.aidial.evaluation.runner.dto;

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
     * Legacy structural JSON object template: resolved via {@code ${{}}} placeholder substitution then
     * evaluated as JSONata — JSON is a syntactic subset of JSONata, so a plain object echoes itself.
     * Mutually exclusive with {@link #jsonataContent}; both converge on the same evaluation path.
     */
    @Schema(
            description = "Legacy structural request body template: a JSON object resolved via ${{}} placeholder "
                    + "substitution, then JSONata-evaluated before being sent (a plain object evaluates to "
                    + "itself). Mutually exclusive with jsonataContent.",
            example = "{ \"model\": \"${{model}}\", \"messages\": \"${{messages}}\" }")
    private Map<String, Object> content;

    /**
     * JSONata source expression template: {@code ${{}}} placeholders are preprocessed into the raw source
     * text, then the combined text is evaluated directly as JSONata. Mutually exclusive with
     * {@link #content}; both converge on the same evaluation path.
     */
    @Schema(
            description = "JSONata source expression request body template: ${{}} placeholders are "
                    + "preprocessed into the source text, then the combined text is evaluated directly as "
                    + "JSONata. Mutually exclusive with content.",
            type = "string",
            example = "{ \"model\": \"gpt-4\", \"messages\": $append($history, [{\"role\": \"user\", \"content\": "
                    + "\"${{question}}\"}]), \"stream\": false }")
    private String jsonataContent;

    @Override
    public String getContentType() {
        return "application/json";
    }
}
