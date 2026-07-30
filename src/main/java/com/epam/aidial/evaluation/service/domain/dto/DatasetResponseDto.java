package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import io.swagger.v3.oas.annotations.media.Schema;
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
public class DatasetResponseDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(example = "My Dataset")
    private String name;

    @Schema(example = "Shared dataset for ingestion regression tests")
    private String description;

    private List<FieldDefinitionDto> testCaseSchema;

    @Schema(example = "true")
    private boolean valid;

    private List<ValidationWarningDto> validationWarnings;

    @Schema(example = "PUBLIC")
    private DatasetVisibility visibility;

    @Schema(example = "1")
    private Long version;

    @Schema(example = "maintainer@example.com")
    private String createdBy;

    @Schema(example = "1704067200000")
    private Long createdAt;

    @Schema(example = "1704067200000")
    private Long updatedAt;
}
