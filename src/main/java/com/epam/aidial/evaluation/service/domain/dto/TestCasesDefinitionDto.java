package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCasesDefinitionDto {

    @Valid
    @Size(max = ValidationConstants.MAX_FACT_FIELDS)
    @Schema(
            example =
                    "[{\"name\":\"expected_status\",\"type\":\"INTEGER\",\"required\":true,\"description\":\"HTTP status code\"},"
                            + "{\"name\":\"response_time_ms\",\"type\":\"NUMBER\",\"required\":false,\"description\":\"Response time in ms\"}]")
    private List<FieldDefinitionDto> factFields;

    /**
     * Server-computed from endpoint schema; clients must not send this in create/update requests.
     * Validation in TestSuiteService rejects requests that include non-empty parameterFields.
     */
    @Schema(
            example =
                    "[{\"name\":\"model\",\"type\":\"STRING\",\"required\":true,\"description\":\"Model ID\"},"
                            + "{\"name\":\"temperature\",\"type\":\"NUMBER\",\"required\":false,\"description\":\"Sampling temperature\"}]")
    private List<FieldDefinitionDto> parameterFields;
}
