package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Binds a metric parameter to a constant value")
public class ConstantBindingSourceDto extends MetricBindingSourceDto {

    @Schema(
            description = "Constant value (any JSON value: string, number, boolean, object, array, or null). "
                    + "A null value is accepted as stored state; if the target metric property is required, "
                    + "soft validation will produce a REQUIRED warning.",
            example = "0.8")
    private Object value;
}
