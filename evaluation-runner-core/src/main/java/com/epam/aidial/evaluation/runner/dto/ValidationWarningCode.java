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

    /**
     * Marks a warning as derived from the CSV import source (a submitted-rows conflict — duplicate
     * {@code turnIndex}, or shared-column values disagreeing across a case's turn rows) rather than from
     * stored test-case data. Such a warning cannot be re-derived by any later pass that recomputes
     * validity from stored state alone, so it is carried forward by that recomputation rather than
     * dropped. Distinct from {@link #ADDITIONAL}, which ordinary schema validation also emits (e.g. for
     * unknown fields) and which recomputation legitimately regenerates or drops on its own.
     */
    SOURCE_CONFLICT
}
