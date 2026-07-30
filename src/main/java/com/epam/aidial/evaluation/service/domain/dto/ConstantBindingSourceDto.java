package com.epam.aidial.evaluation.service.domain.dto;

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
public class ConstantBindingSourceDto extends MetricBindingSourceDto {

    /**
     * Constant value (any JSON value: string, number, boolean, object, array, or null). A null value is
     * accepted as stored state; if the target metric property is required, soft validation will produce a
     * REQUIRED warning.
     */
    private Object value;
}
