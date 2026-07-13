package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
public class TestCaseResponseDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID id;

    @Schema(example = "Smoke test - happy path")
    private String testCaseName;

    private Map<String, Object> data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Row-based multi-turn grouping key. Present only for turns of a conversation; omitted for "
                    + "single-turn test cases.",
            example = "7b1c9f2e-3a4d-4c5b-8e6f-1a2b3c4d5e6f")
    private UUID conversationId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "0-based turn position within the conversation. Omitted for single-turn test cases.",
            example = "0")
    private Integer turnIndex;

    @Schema(example = "true")
    private boolean valid;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description =
                    "Structured validation warnings (fieldName, path, message, code). Omitted when includeWarnings=false.")
    private List<ValidationWarningDto> validationWarnings;

    @Schema(example = "1704067200000")
    private Long createdAt;

    @Schema(example = "1704067200000")
    private Long updatedAt;
}
