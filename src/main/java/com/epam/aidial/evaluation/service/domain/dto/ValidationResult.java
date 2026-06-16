package com.epam.aidial.evaluation.service.domain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of validating data against a JSON Schema.
 * Warnings are truncated to the configured max (e.g. validation.max-warnings-per-case).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    private boolean valid;
    private List<ValidationWarningDto> warnings;
}
