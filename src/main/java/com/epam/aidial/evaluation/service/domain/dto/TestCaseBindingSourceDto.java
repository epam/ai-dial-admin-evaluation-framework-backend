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
@Schema(description = "Binds a metric parameter to a test case column")
public class TestCaseBindingSourceDto extends MetricBindingSourceDto {

    @NotBlank(message = "Column name is required")
    @Schema(description = "Column name from the test suite's testCaseSchema", example = "expected_output")
    private String columnName;

    @Size(max = 2000)
    @Schema(
            description = "Optional JSONata expression evaluated against the resolved test-case column value to "
                    + "select an element. When the data column is an array (e.g. a per-turn user message in a "
                    + "multi-step suite), an expression such as \"$[0]\" (first turn) or \"$[-1]\" (last turn) "
                    + "selects one element; object paths select into a column holding a JSON object. When omitted, "
                    + "the raw column value is used as-is. An expression that matches nothing resolves to null.",
            example = "$[0]")
    private String jsonataExpression;
}
