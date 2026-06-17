package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Minimal identity of a test suite that depends on (is bound to) a dataset.")
public class DatasetDependentSuiteDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(example = "Regression suite")
    private String name;

    @Schema(example = "Nightly regression coverage for the payments dataset")
    private String description;
}
