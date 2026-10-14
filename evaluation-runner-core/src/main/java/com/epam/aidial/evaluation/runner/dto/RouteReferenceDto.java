package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteReferenceDto {

    @NotBlank
    @Size(max = RunnerValidationConstants.MAX_ROUTE_REFERENCE_NAME_LENGTH)
    @Pattern(
            regexp = RunnerValidationConstants.ROUTE_REFERENCE_NAME_PATTERN,
            message = RunnerValidationConstants.ROUTE_REFERENCE_NAME_MESSAGE)
    @Schema(example = "routes/platform/validate-route", description = "Canonical API-managed DIAL Core Route identity")
    private String name;
}
