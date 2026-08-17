package com.epam.aidial.evaluation.runner.dto;

/**
 * Stable identifier for validation warning type (for FE display or i18n).
 */
public enum ValidationWarningCode {
    REQUIRED,
    TYPE,
    FORMAT,
    PATTERN,
    ENUM,
    ADDITIONAL,
    UNRESOLVED_REFERENCE,
    INVALID_OUTPUT_SCHEMA,
    REQUEST_BODY_EVALUATION_ERROR,
    UNKNOWN,
    INVALID_INPUT,
    INVALID_SCOPE
}
