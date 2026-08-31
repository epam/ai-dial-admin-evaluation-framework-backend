package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
@Schema(description = "Binds a metric parameter to a JSONata expression evaluated over {_metrics, data, response}")
public class ExpressionBindingSourceDto extends MetricBindingSourceDto {

    @NotBlank(message = "Expression is required")
    @Schema(
            description = "JSONata expression evaluated against a {_metrics, data, response} frame, where "
                    + "_metrics holds prior TSMDs' outputs accumulated so far for the current row (populated "
                    + "only under inline evaluation; empty otherwise), data is the test case's fields, and "
                    + "response is the row's extracted response columns. Syntax-checked at write time; no "
                    + "cross-TSMD reference validation is performed.",
            example = "$_metrics.`judge`.score.value")
    private String expression;
}
