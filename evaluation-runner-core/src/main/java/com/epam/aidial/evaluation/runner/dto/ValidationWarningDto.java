package com.epam.aidial.evaluation.runner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationWarningDto {

    private String fieldName;
    private String path;
    private String message;
    private ValidationWarningCode code;

    /**
     * 0-based index of the multi-turn turn this warning originates from; null for single-turn cases.
     */
    private Integer turnIndex;
}
