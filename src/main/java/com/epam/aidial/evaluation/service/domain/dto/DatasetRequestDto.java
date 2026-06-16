package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetRequestDto {

    @NotBlank(message = "Name is required")
    @Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH, message = "Name must be less than 263 characters")
    @Schema(example = "My Dataset")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    @Schema(example = "Shared dataset for ingestion regression tests")
    private String description;

    @Valid
    @Schema(description = "Field definitions describing the structure of each test case in this dataset")
    private List<FieldDefinitionDto> testCaseSchema;

    /** Optional. When provided on update, reassigns createdBy to another maintainer. */
    @Size(max = 255)
    @Schema(example = "maintainer@example.com")
    private String createdBy;

    /**
     * Required on create, ignored on update via PUT. Visibility transitions go through the
     * dedicated PATCH /api/v1/datasets/{id}/visibility endpoint.
     */
    @Schema(
            description =
                    "Catalogue visibility — PUBLIC datasets appear in the list endpoint; PRIVATE datasets are scoped to a single suite",
            example = "PUBLIC")
    private DatasetVisibility visibility;

    /**
     * Required on create when {@code visibility = PRIVATE}, forbidden when {@code visibility = PUBLIC}.
     * When supplied, the server atomically inserts this dataset and updates the target suite's
     * {@code dataset_id} in a single transaction so no orphan PRIVATE dataset is observable.
     */
    @Schema(
            description = "Target suite for atomic create-and-bind on PRIVATE dataset creation",
            example = "11111111-1111-1111-1111-111111111111")
    private UUID bindToSuiteId;
}
