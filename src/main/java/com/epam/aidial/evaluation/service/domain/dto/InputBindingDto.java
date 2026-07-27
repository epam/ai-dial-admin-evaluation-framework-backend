package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    @Size(max = 255)
    @Schema(
            description = "Multi-request suites only. Binds this template variable to a response column "
                    + "extracted by a STRICTLY EARLIER request in the suite's chain, named by bare column name "
                    + "(response column names are unique chain-wide, so no request qualification is needed). "
                    + "Rejected with HTTP 400 when it names a column declared by the same request, by a later "
                    + "request, by no request at all, or on a single-request suite — where no earlier request "
                    + "exists. At run time it resolves from the accumulated map of columns extracted so far; "
                    + "when the value is missing the placeholder's declared default "
                    + "(`${{var|type:default}}`) is used, and without a default the request fails.",
            example = "session_id")
    private String responseField;

    /**
     * Validates that exactly one of the three binding sources is set. Per spec: more than one, or none,
     * is invalid (HTTP 400).
     */
    @AssertTrue(message = "Exactly one of dataField, constantValue or responseField must be set")
    public boolean isValidBinding() {
        int sources = 0;
        if (dataField != null && !dataField.isBlank()) {
            sources++;
        }
        if (constantValue != null) {
            sources++;
        }
        if (responseField != null && !responseField.isBlank()) {
            sources++;
        }
        return sources == 1;
    }
}
