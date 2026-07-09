package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stable identifier for validation warning type (for FE display or i18n).
 */
@Schema(description = "Validation warning type code")
public enum ValidationWarningCode {
    @Schema(description = "Missing required property")
    REQUIRED,
    @Schema(description = "Type mismatch")
    TYPE,
    @Schema(description = "Format validation failed")
    FORMAT,
    @Schema(description = "Pattern validation failed")
    PATTERN,
    @Schema(description = "Value not in allowed enum")
    ENUM,
    @Schema(description = "Additional properties not allowed")
    ADDITIONAL,
    @Schema(description = "Reference to a column or resource that does not exist")
    UNRESOLVED_REFERENCE,
    @Schema(description = "Metric output schema is missing, empty, or malformed")
    INVALID_OUTPUT_SCHEMA,
    @Schema(description = "Unmapped validation error")
    UNKNOWN
}
