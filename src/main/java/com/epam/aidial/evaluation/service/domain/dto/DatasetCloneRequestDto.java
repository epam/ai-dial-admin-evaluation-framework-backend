package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for cloning a dataset. Both fields are optional — {@code null} means "inherit from
 * source": {@code name} falls back to {@code DatasetCloneService.deriveCloneName(source.getName())}
 * and {@code description} is copied verbatim from the source. An empty/absent body is valid.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetCloneRequestDto {

    @Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH, message = "Name must be less than 263 characters")
    @Schema(
            description = "Optional name for the clone; when omitted, derived as \"<source> (clone)\"",
            example = "My Dataset (clone)")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    @Schema(
            description = "Optional description for the clone; when omitted, the source's description is copied",
            example = "Clone of the ingestion regression dataset")
    private String description;
}
