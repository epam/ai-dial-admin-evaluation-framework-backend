package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
public class TestCaseRequestDto {

    @NotBlank(message = "Test case name is required")
    @Size(max = 255, message = "Test case name must be less than 255 characters")
    @Schema(example = "Smoke test - happy path")
    private String testCaseName;

    @Builder.Default
    @Schema(example = "{\"prompt\":\"Hello\",\"temperature\":0.7}")
    private Map<String, Object> data = Map.of();

    @Schema(
            description = "Row-based multi-turn grouping key (client-supplied UUID). Rows sharing this value form one "
                    + "multi-turn, ordered by turnIndex. Must be provided together with turnIndex; omit both for a "
                    + "single-turn test case.",
            example = "7b1c9f2e-3a4d-4c5b-8e6f-1a2b3c4d5e6f")
    private UUID multiTurnId;

    @Schema(
            description = "0-based turn position within the multi-turn. Must be provided together with multiTurnId "
                    + "and be in [0, MAX_MULTI_TURN_TURNS).",
            example = "0")
    private Integer turnIndex;
}
