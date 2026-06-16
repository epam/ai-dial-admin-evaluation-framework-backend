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
public class DatasetPublishRequestDto {

    @Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH, message = "Name must be less than 263 characters")
    @Schema(
            description = "New display name for the published dataset; omit to keep the current name",
            example = "Customer Feedback Q1 2026")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    @Schema(
            description = "Description for the published dataset; omit to keep the current description",
            example = "Curated customer feedback test cases for Q1 2026 evaluation runs")
    private String description;
}
