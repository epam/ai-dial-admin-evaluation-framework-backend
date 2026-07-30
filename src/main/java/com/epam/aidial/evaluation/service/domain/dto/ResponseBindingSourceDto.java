package com.epam.aidial.evaluation.service.domain.dto;

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
public class ResponseBindingSourceDto extends MetricBindingSourceDto {

    @NotBlank(message = "Column name is required")
    private String columnName;
}
