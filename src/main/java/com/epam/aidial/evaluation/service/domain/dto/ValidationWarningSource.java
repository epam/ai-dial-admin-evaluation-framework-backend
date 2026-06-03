package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Source of a validation warning - identifies which part of a test case failed validation.
 */
@Schema(description = "Source of the validation warning")
public enum ValidationWarningSource {
    @Schema(description = "Warning from parameters validation")
    PARAMETERS,
    @Schema(description = "Warning from facts validation")
    FACTS
}
