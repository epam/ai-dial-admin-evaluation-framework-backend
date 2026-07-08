package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionWarningDto {

    private String column;
    private String expression;
    private String error;

    /**
     * 0-based conversation turn this warning came from, set only by the multi-step path. Left {@code null}
     * for single-step warnings and omitted from JSON in that case, so single-step output is unchanged.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer stepIndex;
}
