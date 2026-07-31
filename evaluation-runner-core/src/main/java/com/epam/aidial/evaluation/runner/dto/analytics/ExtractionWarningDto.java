package com.epam.aidial.evaluation.runner.dto.analytics;

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
}
