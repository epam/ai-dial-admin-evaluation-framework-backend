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
@Schema(description = "Binds a metric parameter to a response column")
public non-sealed class ResponseBindingSourceDto implements MetricBindingSourceDto {

    @NotBlank(message = "Column name is required")
    @Schema(description = "Column name from the test suite's responseColumns", example = "model_answer")
    private String columnName;
}
