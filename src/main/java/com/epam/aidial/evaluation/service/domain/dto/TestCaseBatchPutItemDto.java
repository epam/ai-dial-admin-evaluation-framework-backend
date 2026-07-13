package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Batch PUT item for updating an existing test case")
public class TestCaseBatchPutItemDto {

    @NotNull(message = "Test case id is required")
    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID id;

    @NotBlank(message = "Test case name is required")
    @Size(max = 255, message = "Test case name must be less than 255 characters")
    @Schema(example = "Smoke test - happy path")
    private String testCaseName;

    @Builder.Default
    @Schema(example = "{\"prompt\":\"Hello\",\"temperature\":0.7}")
    private Map<String, Object> data = Map.of();

    @Schema(
            description = "Row-based multi-turn grouping key (client-supplied UUID); provide together with turnIndex, "
                    + "or omit both for single-turn. PUT is a full replacement, so omitting these clears grouping.",
            example = "7b1c9f2e-3a4d-4c5b-8e6f-1a2b3c4d5e6f")
    private UUID conversationId;

    @Schema(description = "0-based turn position within the conversation.", example = "0")
    private Integer turnIndex;
}
