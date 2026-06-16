package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetDetachRequestDto {

    @Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH, message = "Name exceeds the maximum allowed length")
    @Schema(
            description =
                    "Name for the private dataset clone; omit to derive automatically via \"<source> (clone)\" pattern",
            example = "My Private Dataset")
    private String name;
}
