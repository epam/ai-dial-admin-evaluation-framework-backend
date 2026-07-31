package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InputBindingDto {

    @NotBlank
    private String templateVariable;

    private String dataField;

    private Object constantValue;

    /**
     * Validates that exactly one of dataField or constantValue is set.
     * Per spec: both or neither is invalid (HTTP 400).
     */
    @AssertTrue(message = "Exactly one of dataField or constantValue must be set")
    public boolean isValidBinding() {
        final boolean hasDataField = dataField != null && !dataField.isBlank();
        final boolean hasConstantValue = constantValue != null;
        return hasDataField != hasConstantValue; // XOR: exactly one must be true
    }
}
