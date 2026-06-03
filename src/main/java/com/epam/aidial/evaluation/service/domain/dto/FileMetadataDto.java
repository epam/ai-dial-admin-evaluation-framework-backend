package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Metadata of an uploaded file")
public class FileMetadataDto {

    @Schema(example = "@ef/suites/550e8400-e29b-41d4-a716-446655440000/document.pdf")
    private String path;

    @Schema(example = "document.pdf")
    private String filename;

    @Schema(example = "application/pdf")
    private String contentType;

    @Schema(example = "1048576")
    private long sizeBytes;
}
