package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetVisibilityTransitionDto {

    @NotNull(message = "Target visibility is required")
    @Schema(description = "Target visibility for the transition", example = "PUBLIC")
    private DatasetVisibility visibility;
}
