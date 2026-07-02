package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@Schema(description = "Binds a metric parameter to a response column")
public class ResponseBindingSourceDto extends MetricBindingSourceDto {

    @NotBlank(message = "Column name is required")
    @Schema(description = "Column name from the test suite's responseColumns", example = "model_answer")
    private String columnName;

    @Size(max = 2000)
    @Schema(
            description = "Optional JSONata expression evaluated against the resolved column value to select an "
                    + "element. For a multi-step (multiStep) result the column value is a per-turn array, so an "
                    + "expression such as \"$[0]\" (first turn) or \"$[-1]\" (last turn) selects one turn; object "
                    + "paths select into a column that extracted a JSON object. When omitted, the raw column value "
                    + "is used as-is (the whole array for a multi-step result). An expression that matches nothing "
                    + "resolves to null.",
            example = "$[-1]")
    private String jsonataExpression;
}
